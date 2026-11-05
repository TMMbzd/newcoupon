package com.newcoupon.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 风控决策指标（V6 4A）。
 *
 * <p><b>与 GovernanceMetrics 并存而不合并</b>：那个类管"限流/熔断/租户用量"（治理域，
 * 调用方是过滤器与熔断器）；本类管"风控决策域"（决策四态分布、日志写失败）。
 * 刀 2（核销/退款接线、预算燃烧信号、结算冻结）的指标也落这里。
 *
 * <p><b>标签基数</b>：action 标签只有四个枚举值（PASS/CHALLENGE/REVIEW/REJECT），
 * 天然有界——**绝不把 ruleId/appId/userId 放进标签**（appId 频度=租户数×请求量，
 * userId 是无界维度，见 GovernanceMetrics 类注释的同一条纪律）。命中了哪些规则
 * 去查 risk_decision_log（它有全部因子快照）。
 */
@Component
public class RiskMetrics {

    private final MeterRegistry registry;

    public RiskMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次风控决策（开关开时每次决策 +1）。
     *
     * @param action RiskAction.name()——四值有界；REVIEW/REJECT 的占比就是
     *               风控命中率的盘面（刀 2 复用同一指标）
     */
    public void recordDecision(String action) {
        Counter.builder("coupon_risk_decision_total")
                .tag("action", action)
                .description("风控决策次数（按最终动作四态；REJECT 占比=拦截率，REVIEW 占比=复核队列压力）")
                .register(registry)
                .increment();
    }

    /**
     * 决策日志写失败（不挡业务，但回放失真）。
     *
     * <p><b>为什么必须单独可见</b>：决策日志是同步写的（异步丢日志=回放失真，见
     * RiskDecisionService），所以写失败被刻意"吞掉"以保护业务——没有这条指标，
     * "日志写不进去"就只能靠人翻 ERROR 日志发现，而它意味着这段时间的决策
     * **不可回放、不可复核**。
     */
    public void recordDecisionLogWriteFail() {
        Counter.builder("coupon_risk_decision_log_write_fail_total")
                .description("决策日志写失败次数——不挡业务，但这段时间的决策不可回放（需对存储排障）")
                .register(registry)
                .increment();
    }

    /**
     * 一次降级决策（因子不完整仍出决策，审查 C5）。
     *
     * <p><b>为什么 decision_total 不够、还要这条</b>：degraded 行在 decision_total 里
     * 长得和正常 PASS 一样——"租户漏发 X-Device-Id，唯一自动化规则静默归零"这种事
     * 只能靠它上盘面。reason 有界两值：{@code missing-header}（接入方没发设备头）/
     * {@code counter-unavailable}（设备计数 Redis 故障）。
     */
    public void recordDecisionDegraded(String reason) {
        Counter.builder("coupon_risk_decision_degraded_total")
                .tag("reason", reason)
                .description("因子不完整仍出决策的次数（fail-open 可见性）——missing-header=设备维度没接，counter-unavailable=计数 Redis 故障，budget-not-enabled=活动没开预算，budget-unavailable=预算流水源故障")
                .register(registry)
                .increment();
    }

    /**
     * 退款 REJECT 降级后的结算冻结落账失败（4B）：冻结失败被刻意吞掉以保护已成功的
     * 退款（红线：退款照做 > 账面完整）——没有这条指标，“该冻没冻”只能靠翻 ERROR 日志。
     */
    public void recordRiskHoldPlaceFail() {
        Counter.builder("coupon_risk_hold_place_fail_total")
                .description("风控结算冻结写入失败次数（退款已成功、需人工补 MANUAL hold）")
                .register(registry)
                .increment();
    }

    /**
     * ACTIVE 结算冻结总额盘面（4B）：coupon_risk_hold_amount（gauge，分）。
     * V6 原文的 hold 指标以这个口径落地——RISK_RULE 与 MANUAL 的 ACTIVE hold 合计，
     * 释放/作废后自然回落；由持有 hold Mapper 的装配点注册（每次 scrape 现算 SUM，
     * hold 表量级=风控 REJECT 退款数+人工冻结数，小表可承受）。
     */
    public void registerHoldAmountGauge(java.util.function.Supplier<Number> value) {
        io.micrometer.core.instrument.Gauge
                .builder("coupon_risk_hold_amount", value)
                .description("ACTIVE 结算冻结总额（分）：payable=分账-退款-hold 的盘面信号，涨=有主体应付被扣住")
                .strongReference(true)
                .register(registry);
    }
}
