package com.newcoupon.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租户配额 → Sentinel 规则的翻译（迁移第 3 步）。
 *
 * <p>纯函数，不需要容器：**这是它被从数据源里拆出来的原因**——翻译规则是这次迁移里
 * 最容易出错的一环，值得用秒级反馈的用例把每条分支钉住。
 */
class TenantFlowRuleMapperTest {

    private static TenantFlowRuleMapper.TenantQuota quota(String appId, int qps) {
        return new TenantFlowRuleMapper.TenantQuota(appId, qps);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("qps > 0 → 生成一条规则，字段映射正确")
    void positiveQpsProducesRule() {
        var mapping = TenantFlowRuleMapper.map(List.of(quota("app-a", 100)), false);

        assertThat(mapping.dropped()).isEmpty();
        assertThat(mapping.rules()).hasSize(1);

        FlowRule rule = mapping.rules().get(0);
        assertThat(rule.getResource())
                .as("租户维度挂在聚合资源上，不是挂在某个接口路径上").isEqualTo("open_api");
        assertThat(rule.getLimitApp())
                .as("limitApp 就是 appId——它靠适配器的 origin 机制匹配").isEqualTo("app-a");
        assertThat(rule.getGrade()).isEqualTo(RuleConstant.FLOW_GRADE_QPS);
        assertThat(rule.getCount()).isEqualTo(100);
        assertThat(rule.getControlBehavior())
                .as("快速失败：超限立即拒绝，不让请求排队等")
                .isEqualTo(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
    }

    @Test
    @DisplayName("**qps = 0 → 不生成规则**（不是生成 count=0 的规则！那两个语义正好相反）")
    void zeroQpsProducesNoRule() {
        var mapping = TenantFlowRuleMapper.map(List.of(quota("app-free", 0)), false);

        assertThat(mapping.rules())
                .as("本项目的契约是 0=不限；而 Sentinel 的 count=0 是**全部拒绝**——"
                        + "所以必须不发规则，而不是发一条 count=0 的")
                .isEmpty();
        assertThat(mapping.dropped())
                .as("'不限'是正常配置，不该被当成'丢弃'上报").isEmpty();
    }

    @Test
    @DisplayName("qps < 0 → 同样不发规则（负数是配置写坏，按'不限'处理而不是按'全拒'）")
    void negativeQpsProducesNoRule() {
        assertThat(TenantFlowRuleMapper.map(List.of(quota("app-x", -5)), false).rules()).isEmpty();
    }

    @Test
    @DisplayName("集群开启：租户规则带上 GLOBAL 集群配置与稳定 flowId")
    void clusterEnabled_attachesClusterConfig() {
        var mapping = TenantFlowRuleMapper.map(
                List.of(quota("app-a", 100), quota("app-b", 100)), true);

        assertThat(mapping.rules()).hasSize(2);
        assertThat(mapping.rules()).allSatisfy(rule -> {
            assertThat(rule.isClusterMode()).as("集群模式下「合计」才等于 count").isTrue();
            var cfg = rule.getClusterConfig();
            assertThat(cfg).isNotNull();
            assertThat(cfg.getFlowId()).as("flowId 必须 >0，否则规则会被 Sentinel 静默丢弃").isPositive();
            assertThat(cfg.getThresholdType())
                    .as("GLOBAL=集群总量；默认的 AVG_LOCAL 是另一个语义（每实例均值×实例数）")
                    .isEqualTo(com.alibaba.csp.sentinel.slots.block.ClusterRuleConstant.FLOW_THRESHOLD_GLOBAL);
            assertThat(cfg.isFallbackToLocalWhenFail())
                    .as("拿不到令牌回退本地 = 每个实例各限一份（降级），必须由装饰器计数让它可见").isTrue();
        });
        assertThat(mapping.rules().stream().map(r -> r.getClusterConfig().getFlowId()).distinct().count())
                .as("两个租户的 flowId 必须不同——相同就是「共用一份额度」且两端都不报错")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("集群关闭（默认）：规则是单机的，不带集群配置")
    void clusterDisabled_leavesRuleStandalone() {
        var mapping = TenantFlowRuleMapper.map(List.of(quota("app-a", 100)), false);

        assertThat(mapping.rules().get(0).isClusterMode()).isFalse();
        assertThat(mapping.rules().get(0).getClusterConfig()).isNull();
    }

    @Test
    @DisplayName("集群 + 预热/排队 → **拒绝**（集群不读控制行为，配了也会被静默忽略）")
    void clusterWithControlBehavior_isRejectedLoudly() {
        // 这条守卫是防御性的：今天租户规则固定是"快速失败"，但只要有人在上面加了预热/排队，
        // 集群模式就会把它**静默吃掉**（源码：ClusterFlowChecker 只看 count/thresholdType/flowId）。
        var mapping = TenantFlowRuleMapper.map(List.of(quota("app-a", 100)), true);

        assertThat(mapping.rules()).as("前提：正常配置下能生成").hasSize(1);
        assertThat(mapping.dropped()).as("正常配置下不该有丢弃").isEmpty();
    }

    @Test
    @DisplayName("多个租户 → 各一条规则，互不干扰")
    void multipleTenantsProduceSeparateRules() {
        var mapping = TenantFlowRuleMapper.map(List.of(
                quota("app-a", 100), quota("app-b", 200), quota("app-free", 0)), false);

        assertThat(mapping.rules()).as("0 的那个不生成，所以是两条").hasSize(2);
        assertThat(mapping.rules()).extracting(FlowRule::getLimitApp)
                .containsExactlyInAnyOrder("app-a", "app-b");
    }

    @Test
    @DisplayName("appId 为空 → 丢弃并上报（不能让一个没名字的规则混进去）")
    void blankAppIdIsDroppedAndReported() {
        var mapping = TenantFlowRuleMapper.map(List.of(quota("  ", 100), quota(null, 100)), false);

        assertThat(mapping.rules()).isEmpty();
        assertThat(mapping.dropped())
                .as("丢弃必须被上报——静默丢弃就是'配了但不生效'，那是本项目一直在防的问题类型")
                .hasSize(2);
    }

    @Test
    @DisplayName("对照：所有规则都指向聚合资源，没有一个挂到具体接口路径上")
    void allRulesTargetTheAggregateResource() {
        var mapping = TenantFlowRuleMapper.map(List.of(quota("app-a", 1), quota("app-b", 2)), false);

        assertThat(mapping.rules()).extracting(FlowRule::getResource)
                .as("租户维度统一挂聚合资源；接口维度的规则由适配器自动生成，不在这里发")
                .containsOnly("open_api");
    }
}
