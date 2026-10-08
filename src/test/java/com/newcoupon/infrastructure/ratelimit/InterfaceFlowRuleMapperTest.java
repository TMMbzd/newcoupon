package com.newcoupon.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 接口维度 → Sentinel 规则（迁移第 8 步，2026-10-08）。
 *
 * <p><b>为什么它是纯函数</b>：翻译规则是这次迁移里最容易出错、又最需要反复验证的一环
 * （0 / 不限 / 全拒的语义陷阱、limitApp 的选择、资源名对齐）。拆出来就能用普通单测覆盖所有分支，
 * 不必起 Spring 上下文与容器。
 */
class InterfaceFlowRuleMapperTest {

    private static final Set<String> PATTERNS = Set.of("/open/v1/coupons", "/open/v1/templates/{id}");

    @Test
    @DisplayName("关着 → 一条规则都不发（不是发一条 count=0 的：那是全拒，方向正好相反）")
    void disabled_sendsNoRule() {
        assertThat(InterfaceFlowRuleMapper.map(PATTERNS, false, 100, 0, 0).rules()).isEmpty();
    }

    @Test
    @DisplayName("qps<=0（0 = 不限）→ 一条规则都不发")
    void unlimited_sendsNoRule() {
        assertThat(InterfaceFlowRuleMapper.map(PATTERNS, true, 0, 0, 0).rules()).isEmpty();
    }

    @Test
    @DisplayName("启用 → 每个路径模式一条规则，limitApp=default（不区分来源：这是平台级上限）")
    void enabled_sendsOneRulePerPattern() {
        List<FlowRule> rules = InterfaceFlowRuleMapper.map(PATTERNS, true, 50, 0, 0).rules();

        assertThat(rules).hasSize(PATTERNS.size());
        assertThat(rules).allSatisfy(rule -> {
            assertThat(rule.getLimitApp())
                    .as("接口维度是「这个接口被全平台打了多少」，与哪个租户打的无关")
                    .isEqualTo("default");
            assertThat(rule.getCount()).isEqualTo(50);
            assertThat(rule.getGrade())
                    .as("预热与匀速排队只在 QPS 档上生效；非 QPS 会被静默降级"
                            + "（注意常量值：FLOW_GRADE_QPS=1、FLOW_GRADE_THREAD=0）")
                    .isEqualTo(com.alibaba.csp.sentinel.slots.block.RuleConstant.FLOW_GRADE_QPS);
            assertThat(rule.isClusterMode())
                    .as("接口维度走单机（设计 §5.3）：集群模式下预热/排队会静默失效").isFalse();
        });
        assertThat(rules.stream().map(FlowRule::getResource))
                .as("资源名必须与适配器 enter 的那个名字同源（路径模式），否则规则配了也不命中——"
                        + "而 Sentinel 对不命中的规则是完全静默的")
                .containsExactlyInAnyOrderElementsOf(PATTERNS);
    }

    @Test
    @DisplayName("预热与排队：按配置挑控制行为（0/1/2/3 四种），并把参数带上")
    void warmupAndQueue_shapeTheControlBehavior() {
        var warmupOnly = InterfaceFlowRuleMapper.map(PATTERNS, true, 50, 30, 0).rules().get(0);
        assertThat(warmupOnly.getControlBehavior()).isEqualTo(1);   // WARM_UP
        assertThat(warmupOnly.getWarmUpPeriodSec()).isEqualTo(30);

        var queueOnly = InterfaceFlowRuleMapper.map(PATTERNS, true, 50, 0, 200).rules().get(0);
        assertThat(queueOnly.getControlBehavior()).isEqualTo(2);    // RATE_LIMITER
        assertThat(queueOnly.getMaxQueueingTimeMs()).isEqualTo(200);

        var both = InterfaceFlowRuleMapper.map(PATTERNS, true, 50, 30, 200).rules().get(0);
        assertThat(both.getControlBehavior())
                .as("1.8.10 有「预热+排队」的合成控制器（3），不需要拼两条规则"
                        + "（两条规则是 AND，各自限各自的桶，拼不出一个桶的两种能力）").isEqualTo(3);
        assertThat(both.getWarmUpPeriodSec()).isEqualTo(30);
        assertThat(both.getMaxQueueingTimeMs()).isEqualTo(200);

        var neither = InterfaceFlowRuleMapper.map(PATTERNS, true, 50, 0, 0).rules().get(0);
        assertThat(neither.getControlBehavior()).as("都不配 = 快速失败（默认形态）").isZero();
    }

    @Test
    @DisplayName("开关开了但目录是空的 → 不发规则，但**必须报出来**（否则接口维度静默不存在）")
    void emptyCatalog_isReported() {
        InterfaceFlowRuleMapper.Mapping mapping = InterfaceFlowRuleMapper.map(Set.of(), true, 50, 0, 0);

        assertThat(mapping.rules()).isEmpty();
        assertThat(mapping.dropped())
                .as("「开了但没生效」是最难查的一类状态，必须留下可上报的痕迹")
                .hasSize(1);
    }
}
