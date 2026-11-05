package com.newcoupon.application.service;

import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskScene;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import com.newcoupon.infrastructure.risk.DeviceDailyCounter;
import java.time.LocalDate;
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

    private final DeviceDailyCounter counter;
    private final GovernanceMetrics metrics;
    private final int maxPerDay;

    public DeviceDailyRule(DeviceDailyCounter counter,
                           GovernanceMetrics metrics,
                           @Value("${coupon.risk.device-daily.max-per-day:10}") int maxPerDay) {
        this.counter = counter;
        this.metrics = metrics;
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
        return false;   // INCR 是副作用，回放跳过（见类注释）
    }

    @Override
    public Optional<RiskAction> evaluate(RiskContext ctx, RiskFactors factors) {
        factors.put("device.maxPerDay", maxPerDay);
        if (ctx.deviceId() == null || ctx.deviceId().isBlank()) {
            // 因子缺失：跳过并标 degraded——不挡请求（接入方没接设备头 ≠ 攻击）
            factors.put("device.skipped", "missing-header");
            factors.markDegraded();
            return Optional.empty();
        }
        long count;
        try {
            count = counter.increment(ctx.appId(), ctx.deviceId(), LocalDate.now());
        } catch (RuntimeException e) {
            // 因子源不可用：fail-open（放行）+ degraded + 可见——与 IpRateGate 同款三件套
            factors.put("device.skipped", "counter-unavailable");
            factors.markDegraded();
            metrics.recordLimitFailOpen(METRIC_DIMENSION);
            return Optional.empty();
        }
        factors.put("device.count", count);
        return count > maxPerDay ? Optional.of(RiskAction.REJECT) : Optional.empty();
    }
}
