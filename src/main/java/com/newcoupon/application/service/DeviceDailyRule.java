package com.newcoupon.application.service;

import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskScene;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import com.newcoupon.infrastructure.metrics.RiskMetrics;
import com.newcoupon.infrastructure.risk.DeviceDailyCounter;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 设备日限规则（ruleId=R_DEVICE_DAILY，V6 4A）：同设备当日领券**尝试**数超阈 → REJECT。
 *
 * <p><b>挡的攻击形态</b>：脚本一台设备换着 userId 刷券（库存/限领只防"每个用户领几张"，
 * 不防"一台设备来多少个用户"）。阈值默认 10（coupon.risk.device-daily.max-per-day）。
 *
 * <p><b>透传维度的三态处理</b>（X-Device-Id 是请求方可控的头，不是可信指纹）：
 * <ul>
 *   <li>缺失 → 跳过本规则 + degraded（因子缺失不挡请求——S2 透传位设计：
 *       接入方没接设备头时风控不能因此拒绝它的全部用户）；</li>
 *   <li>带了 → Redis 计数（INCR+EXPIRE，键 c:risk:dev:{appId}:{deviceId}:{yyyyMMdd}）；
 *       Redis 不可用 → 跳过 + degraded + fail-open 指标（dimension=risk_device，
 *       与 IpRateGate 同款哲学：可用性防线不因依赖故障误杀全量，但失效必须可见）；</li>
 *   <li>超阈 → REJECT（第 max+1 次拒绝——前 max 次放行，计数含被拒的尝试）。</li>
 * </ul>
 *
 * <p><b>不可回放</b>（replayable=false）：计数是副作用——回放端点跳过本规则并如实列出，
 * 不用"今天的计数"冒充"当时的计数"（那会是把两次不同的观察混进一次复盘）。
 */
@Component
@Order(200)   // 评估序第二：名单（含白名单短路）之后
public class DeviceDailyRule implements RiskRule {

    /** fail-open 指标维度码：与 risk_ip（IP 频控）、开放面 ip（配额）互不合并计数 */
    static final String METRIC_DIMENSION = "risk_device";

    /** degraded 指标的两个 reason（审查 C5）：设备维度缺失/计数源不可用 */
    static final String REASON_MISSING_HEADER = "missing-header";
    static final String REASON_COUNTER_UNAVAILABLE = "counter-unavailable";

    private final DeviceDailyCounter counter;
    private final GovernanceMetrics governanceMetrics;
    private final RiskMetrics riskMetrics;
    private final int maxPerDay;

    public DeviceDailyRule(DeviceDailyCounter counter,
                           GovernanceMetrics governanceMetrics,
                           RiskMetrics riskMetrics,
                           @Value("${coupon.risk.device-daily.max-per-day:10}") int maxPerDay) {
        this.counter = counter;
        this.governanceMetrics = governanceMetrics;
        this.riskMetrics = riskMetrics;
        this.maxPerDay = maxPerDay;
    }

    @Override
    public String ruleId() {
        return "R_DEVICE_DAILY";
    }

    @Override
    public boolean supports(RiskScene scene) {
        return scene == RiskScene.RECEIVE;   // 只防"无成本获取资产"的入口；退款/核销另有政策上限
    }

    @Override
    public boolean replayable() {
        // 回放走 replay() 的"日志因子×当前阈值"纯重算（不碰计数器）——见下
        return true;
    }

    @Override
    public Optional<RiskAction> evaluate(RiskContext ctx, RiskFactors factors) {
        factors.put("device.maxPerDay", maxPerDay);
        if (ctx.deviceId() == null || ctx.deviceId().isBlank()) {
            // 因子缺失：跳过并标 degraded——不挡请求（接入方没接设备头 ≠ 攻击）。
            // 必须上指标（审查 C5）：租户漏发设备头时"唯一自动化规则静默归零"要出现在盘面上
            factors.put("device.skipped", REASON_MISSING_HEADER);
            factors.markDegraded();
            riskMetrics.recordDecisionDegraded(REASON_MISSING_HEADER);
            return Optional.empty();
        }
        long count;
        try {
            count = counter.increment(ctx.appId(), ctx.deviceId(), LocalDate.now());
        } catch (RuntimeException e) {
            // 因子源不可用：fail-open（放行）+ degraded + 可见——与 IpRateGate 同款三件套
            factors.put("device.skipped", REASON_COUNTER_UNAVAILABLE);
            factors.markDegraded();
            governanceMetrics.recordLimitFailOpen(METRIC_DIMENSION);
            riskMetrics.recordDecisionDegraded(REASON_COUNTER_UNAVAILABLE);
            return Optional.empty();
        }
        factors.put("device.count", count);
        return count > maxPerDay ? Optional.of(RiskAction.REJECT) : Optional.empty();
    }

    /**
     * 回放（审查 B 簇）：**日志 count × 当前阈值**的纯内存重算——不 INCR、不碰 Redis。
     *
     * <p>为什么必须做阈值重算而不是整体跳过：设备日限是稳态下 REJECT 的最大来源，
     * 整体跳过会让这些行回放恒 PASS——误杀申诉拿到假答案（"拦他的条件已变"，用户重试
     * 仍 7003），"改阈值回放量化影响"也物理不可行（改前改后逐字节相同）。
     * 阈值重算后：阈值未变→REJECT（如实）；阈值调大→翻转成放行（量化可见）。
     * 当时没算出 count（无头/Redis down 的 degraded 行）→ 如实说算不了，返回 empty。
     */
    @Override
    public Optional<RiskAction> replay(RiskContext ctx, Map<String, Object> loggedFactors,
                                       RiskFactors out) {
        Object logged = loggedFactors.get("device.count");
        if (!(logged instanceof Number number)) {
            // 当时该规则没说话（无设备头/计数不可用）：回放同样不说话——不能拿"今天的计数"
            // 冒充"当时的计数"（两次不同的观察混进一次复盘）
            out.put("device.replay", "no-logged-count");
            return Optional.empty();
        }
        out.put("device.replay", "threshold-recompute");
        out.put("device.count", number.longValue());
        out.put("device.maxPerDay", maxPerDay);
        return number.longValue() > maxPerDay ? Optional.of(RiskAction.REJECT) : Optional.empty();
    }
}
