package com.newcoupon.application.service;

import com.newcoupon.domain.repository.PricingSnapshotRepository;
import java.time.LocalDateTime;
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
 * <p>清理是幂等的：多实例同时删同一批最多是重复 0 行；不需要分布式锁。
 * 单轮最多 20 批 × 500，避免长事务/长扫描拖慢交易库。
 */
@Service
public class PricingSnapshotCleanupService {

    private static final Logger log = LoggerFactory.getLogger(PricingSnapshotCleanupService.class);
    private static final int BATCH = 500;
    private static final int MAX_BATCHES = 20;
    /** 历史快照保留 7 天供审计/客诉追溯 */
    private static final int RETENTION_DAYS = 7;

    private final PricingSnapshotRepository repository;

    public PricingSnapshotCleanupService(PricingSnapshotRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "${coupon.pricing.cleanup-cron:0 17 * * * ?}",
            zone = "Asia/Shanghai")
    public void cleanup() {
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
    }
}
