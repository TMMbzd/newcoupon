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
        var mapping = TenantFlowRuleMapper.map(List.of(quota("app-a", 100)));

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
        var mapping = TenantFlowRuleMapper.map(List.of(quota("app-free", 0)));

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
        assertThat(TenantFlowRuleMapper.map(List.of(quota("app-x", -5))).rules()).isEmpty();
    }

    @Test
    @DisplayName("多个租户 → 各一条规则，互不干扰")
    void multipleTenantsProduceSeparateRules() {
        var mapping = TenantFlowRuleMapper.map(List.of(
                quota("app-a", 100), quota("app-b", 200), quota("app-free", 0)));

        assertThat(mapping.rules()).as("0 的那个不生成，所以是两条").hasSize(2);
        assertThat(mapping.rules()).extracting(FlowRule::getLimitApp)
                .containsExactlyInAnyOrder("app-a", "app-b");
    }

    @Test
    @DisplayName("appId 为空 → 丢弃并上报（不能让一个没名字的规则混进去）")
    void blankAppIdIsDroppedAndReported() {
        var mapping = TenantFlowRuleMapper.map(List.of(quota("  ", 100), quota(null, 100)));

        assertThat(mapping.rules()).isEmpty();
        assertThat(mapping.dropped())
                .as("丢弃必须被上报——静默丢弃就是'配了但不生效'，那是本项目一直在防的问题类型")
                .hasSize(2);
    }

    @Test
    @DisplayName("对照：所有规则都指向聚合资源，没有一个挂到具体接口路径上")
    void allRulesTargetTheAggregateResource() {
        var mapping = TenantFlowRuleMapper.map(List.of(quota("app-a", 1), quota("app-b", 2)));

        assertThat(mapping.rules()).extracting(FlowRule::getResource)
                .as("租户维度统一挂聚合资源；接口维度的规则由适配器自动生成，不在这里发")
                .containsOnly("open_api");
    }
}
