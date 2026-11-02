package com.newcoupon.application.service;

import com.newcoupon.domain.repository.PricingSnapshotRepository;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 价格快照清理（环 2）。
 *
 * <p>两步走，不直接删 ACTIVE 行：
 * <ol>
 *   <li>过期行置空 {@code live_digest}（释放同摘要唯一键，允许重建；历史行保留审计）；</li>
 *   <li>只删除“已过期且 live_digest 为空、并超过 7 天保留期”的历史行。</li>
 * </ol>
 *
 * <p>清理是幂等的：多实例同时删同一批最多是重复 0 行。单轮最多 20 批 × 500，
 * 避免长事务/长扫描拖慢交易库。
 *
 * <p>多实例调度：@Scheduled 与 @XxlJob 两个通道都先抢 RedisScanLock（一小时一轮，
 * TTL 30 分钟 > 最坏轮时长）。抢不到锁让路；Redis 本身不可用也让路——下个小时自愈，
 * 不值得在 Redis 故障时让每个实例同时删库。锁只消重复负载，正确性仍靠条件更新。
 */
@Service
public class PricingSnapshotCleanupService {

    private static final Logger log = LoggerFactory.getLogger(PricingSnapshotCleanupService.class);
    private static final int BATCH = 500;
    private static final int MAX_BATCHES = 20;
    /** 历史快照保留 7 天供审计/客诉追溯 */
    private static final int RETENTION_DAYS = 7;

    private static final String LOCK_KEY = "c:lock:pricing-snapshot-cleanup";
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final PricingSnapshotRepository repository;
    private final RedisScanLock scanLock;
    private final MeterRegistry meterRegistry;

    public PricingSnapshotCleanupService(PricingSnapshotRepository repository,
                                         RedisScanLock scanLock,
                                         MeterRegistry meterRegistry) {
        this.repository = repository;
        this.scanLock = scanLock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 调度入口（双通道共用）：抢锁后跑一轮清理。
     *
     * <p>返回：清理行数（>=0）；0 也可能是“让路”；-1=本轮失败（已记指标与日志）。
     * 锁释放失败只打日志，不把已经删完/置空的轮次判成失败。
     */
    public int runOnce() {
        String owner = "pricing-cleanup-" + UUID.randomUUID();
        boolean acquired;
        try {
            acquired = scanLock.tryLock(LOCK_KEY, owner, LOCK_TTL);
        } catch (RuntimeException e) {
            metric("lock_error");
            log.warn("PRICING_SNAPSHOT_CLEANUP_LOCK_ERROR 本轮让路（下个小时重试）", e);
            return 0;
        }
        if (!acquired) {
            metric("skipped");
            log.debug("pricing snapshot cleanup skipped: another instance holds {}", LOCK_KEY);
            return 0;
        }
        long started = System.nanoTime();
        try {
            int rows = cleanupInternal();
            metric("success");
            return rows;
        } catch (RuntimeException e) {
            metric("failed");
            log.error("PRICING_SNAPSHOT_CLEANUP_FAIL 本轮清理失败（下个小时重试）", e);
            return -1;
        } finally {
            try {
                scanLock.unlock(LOCK_KEY, owner);
            } catch (RuntimeException e) {
                log.warn("pricing cleanup lock release failed (TTL will expire): {}", LOCK_KEY, e);
            }
            recordDuration(started);
        }
    }

    /** 手动补跑/测试入口：不加锁，可重复执行。 */
    public void cleanup() {
        cleanupInternal();
    }

    @Scheduled(cron = "${coupon.pricing.cleanup-cron:0 17 * * * ?}",
            zone = "Asia/Shanghai")
    public void scheduledCleanup() {
        runOnce();
    }

    /** XXL-JOB 生产通道；handler 名需在调度中心注册。 */
    @XxlJob("pricingSnapshotCleanupJob")
    public void xxlCleanup() {
        if (runOnce() < 0) {
            markXxlFailed("pricing snapshot cleanup failed");
        }
    }

    /** @return 本轮置空 live_digest 的行数 + 物理删除的行数。 */
    private int cleanupInternal() {
        LocalDateTime now = LocalDateTime.now();
        int expired = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n = repository.expireLive(now, BATCH);
            expired += n;
            if (n < BATCH) {
                break;
            }
        }
        int deleted = 0;
        LocalDateTime retentionCutoff = now.minusDays(RETENTION_DAYS);
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n = repository.deleteExpired(retentionCutoff, BATCH);
            deleted += n;
            if (n < BATCH) {
                break;
            }
        }
        if (expired > 0 || deleted > 0) {
            log.info("PRICING_SNAPSHOT_CLEANUP expired={} deleted={} retentionDays={}",
                    expired, deleted, RETENTION_DAYS);
        }
        if (expired > 0) {
            metricRows("expire", expired);
        }
        if (deleted > 0) {
            metricRows("delete", deleted);
        }
        return expired + deleted;
    }

    private void metric(String result) {
        try {
            meterRegistry.counter("pricing_snapshot_cleanup_total", "result", result).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不能影响清理
        }
    }

    private void metricRows(String action, int rows) {
        try {
            meterRegistry.counter("pricing_snapshot_cleanup_rows_total", "action", action)
                    .increment(rows);
        } catch (RuntimeException ignore) {
            // 指标失败不能影响清理
        }
    }

    private void recordDuration(long startedNanos) {
        try {
            meterRegistry.timer("pricing_snapshot_cleanup_duration_seconds")
                    .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignore) {
            // 指标失败不能影响清理
        }
    }

    private static void markXxlFailed(String message) {
        try {
            XxlJobHelper.handleFail(message);
        } catch (RuntimeException ignore) {
            // 非 XXL 调度线程（测试/手动）没有上下文
        }
    }
}
