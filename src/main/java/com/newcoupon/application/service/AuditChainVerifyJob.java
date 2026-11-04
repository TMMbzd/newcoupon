package com.newcoupon.application.service;

import com.newcoupon.application.dto.AuditDtos.AuditVerifyResult;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计哈希链巡检任务（V6 2C）：每轮全链重算，断链只告警不修复。
 *
 * <p><b>为什么不自动修复</b>：哈希链没有"修复"这个动作——断链意味着有行被删/被改，
 * 唯一正确的响应是让人来看；自动"接上链"等于亲手销毁被篡改的证据（并把平台自己
 * 变成篡改者）。这与 BudgetReconcileJob 的"钱账只告警"是同一条纪律。
 *
 * <p><b>双通道</b>：XXL-JOB（couponAuditChainVerifyJob，主通道——可按需手动触发、
 * 有执行历史）+ @Scheduled 降级通道（XXL 不可用时兜底），照 BudgetReconcileJob 的
 * 既有模式；Redis 扫描锁保证多实例单飞——正确性不依赖锁（verify 只读，重复跑无害）。
 *
 * <p><b>默认关</b>（{@code coupon.audit.verify.enabled}）：巡检是全表扫描，
 * 开不开、多久跑一轮由部署方按数据量决定（application.yml 有条目与注释，
 * 不留 @ConditionalOnProperty 静默不装配的坑）。
 *
 * <p><b>活性可观测（审查 M2）</b>：只有失败才递增指标的巡检，"从未跑过"与"跑且健康"
 * 在监控上不可区分——断链暴露窗口会从一天劣化到一个季度。每轮无论成败递增
 * {@code coupon_audit_chain_verify_total{result=ok|broken|error}}；成功轮更新
 * {@code coupon_audit_chain_last_success_epoch_ms} gauge（告警：超 fixed-delay+1h
 * 未动=巡检没在跑，先查开关与调度器再谈断链）。
 */
@Component
@ConditionalOnProperty(prefix = "coupon.audit.verify", name = "enabled", havingValue = "true")
public class AuditChainVerifyJob {

    private static final Logger log = LoggerFactory.getLogger(AuditChainVerifyJob.class);
    private static final String LOCK_KEY = "c:lock:audit-chain-verify";
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);   // 全表扫描量级留足余量
    /** 断链指标（任务与 verify 端点共用这一个名字，reason 标签区分断点原因）。 */
    static final String BROKEN_METRIC = "coupon_audit_chain_broken_total";
    /** 活性指标：每轮必计数（ok/broken/error 三值区分，"从未跑过"=三值全无）。 */
    static final String VERIFY_ROUND_METRIC = "coupon_audit_chain_verify_total";
    /** 最近一次成功轮的 epoch 毫秒（gauge；告警口径见 runbook 审计查询.md §6）。 */
    static final String LAST_SUCCESS_METRIC = "coupon_audit_chain_last_success_epoch_ms";

    private final AuditVerifyService verifyService;
    private final RedisScanLock scanLock;
    private final MeterRegistry meterRegistry;
    /** gauge 的强引用载体：Micrometer 的 gauge 是弱引用，字段被回收 gauge 就变 NaN。 */
    private final java.util.concurrent.atomic.AtomicLong lastSuccessEpochMs = new java.util.concurrent.atomic.AtomicLong(0);

    public AuditChainVerifyJob(AuditVerifyService verifyService,
                               RedisScanLock scanLock,
                               MeterRegistry meterRegistry) {
        this.verifyService = verifyService;
        this.scanLock = scanLock;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge(LAST_SUCCESS_METRIC, lastSuccessEpochMs);
    }

    /**
     * 单轮巡检：与 POST /api/audit/verify 端点同一份重算逻辑（一个
     * {@link AuditVerifyService} 两个入口），不存在"端点说好、任务说坏"的两套口径。
     *
     * @return true=全链一致；false=发现断点或本轮异常（均已计数+日志）
     */
    @XxlJob("couponAuditChainVerifyJob")
    @Scheduled(fixedDelayString = "${coupon.audit.verify.fixed-delay-ms:86400000}",
            initialDelayString = "${coupon.audit.verify.initial-delay-ms:3600000}")
    public boolean runOnce() {
        String owner = "audit-verify-" + UUID.randomUUID();
        if (!scanLock.tryLock(LOCK_KEY, owner, LOCK_TTL)) {
            log.info("audit chain verify skipped: lock held by another instance");
            return true;   // 别的实例在跑同一件事，结果等价
        }
        try {
            AuditVerifyResult result = verifyService.verifyChain();
            if (result.ok()) {
                lastSuccessEpochMs.set(System.currentTimeMillis());
                roundMetric("ok");
                log.info("audit chain verify ok: checked={} rows", result.checked());
                return true;
            }
            // 断链四连：轮次计数 + 断链指标（告警按它响铃）+ ERROR 日志 + 断点详情（runbook 处置）
            roundMetric("broken");
            brokenMetric(result.reason());
            log.error("AUDIT CHAIN BROKEN (daily job): breakAtId={} reason={} checked={} "
                            + "——断链=有行被删/被改，不自动修复（修复=销毁证据），按 runbook 审计查询.md 处置",
                    result.breakAtId(), result.reason(), result.checked());
            return false;
        } catch (RuntimeException e) {
            // 本轮自身失败（DB/Redis 异常）：与"链断了"分开计数——排障方向完全不同
            roundMetric("error");
            log.error("audit chain verify round failed (next round retries)", e);
            return false;
        } finally {
            scanLock.unlock(LOCK_KEY, owner);
        }
    }

    private void roundMetric(String result) {
        try {
            meterRegistry.counter(VERIFY_ROUND_METRIC, "result", result).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不能影响巡检本身
        }
    }

    private void brokenMetric(String reason) {
        try {
            meterRegistry.counter(BROKEN_METRIC, "reason", String.valueOf(reason)).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不能影响巡检本身
        }
    }
}
