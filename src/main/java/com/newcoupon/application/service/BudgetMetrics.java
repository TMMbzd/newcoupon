package com.newcoupon.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 预算域最小指标（V6 阶段 1C）：可用额 gauge + 拒绝分布 counter + 任务状态 counter。
 *
 * <p><b>available gauge 的口径</b>：值来自每次账本动作后的账户快照（total-reserved-consumed），
 * 由 {@link BudgetTxService} 在写流水的同一点更新——指标与账本同源，不会各自漂移。
 * 多实例部署下每个实例只见过自己经手的动作，gauge 是"本实例最后已知值"；
 * 精确全量值以对账任务/DB 为准（指标是观测，不是事实源）。
 */
@Component
public class BudgetMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<Long, AtomicLong> availableByAccount =
            new ConcurrentHashMap<>();

    public BudgetMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 更新某账户的可用额 gauge（首次触达时注册，tag 用活动 id——账户数量=运营建的活动数，
     * 基数可控；无界的 tag 维度才是指标 cardinality 事故的来源）。
     */
    public void updateAvailable(long accountId, long activityId, long available) {
        try {
            AtomicLong value = availableByAccount.computeIfAbsent(accountId, id ->
                    registry.gauge("coupon_budget_available",
                            java.util.List.of(io.micrometer.core.instrument.Tag.of(
                                    "activity", String.valueOf(activityId))),
                            new AtomicLong(available)));
            value.set(available);
        } catch (RuntimeException ignore) {
            // 指标失败不能影响账本主流程
        }
    }

    /** 预算拒绝分布：reason=chunk（批量建任务）/ single（同步领取）/ code（兑换码）。 */
    public void recordRejected(String reason) {
        try {
            counter("coupon_budget_rejected_total", reason).increment();
        } catch (RuntimeException ignore) {
            // 同上
        }
    }

    /** 发放任务状态分布：status=created/success/canceled（受理与终态收尾各计一次）。 */
    public void recordGrantTaskStatus(String status) {
        try {
            Counter counter = registry.counter("coupon_grant_tasks_total", "status", status);
            counter.increment();
        } catch (RuntimeException ignore) {
            // 同上
        }
    }

    private Counter counter(String name, String reason) {
        return registry.counter(name, "reason", reason);
    }
}
