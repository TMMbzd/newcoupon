package com.newcoupon.domain.repository;

import com.newcoupon.domain.pricing.PricingSnapshot;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 价格快照仓储端口（环 2）。
 *
 * <p>独立非分片表；按 pricing_id / live_digest 点查。同摘要“活快照”唯一：
 * 并发写由 {@code uk_live_digest} 裁决，应用层捕获后回读。
 */
public interface PricingSnapshotRepository {

    Optional<PricingSnapshot> findByPricingId(String pricingId);

    /** 只查 ACTIVE（live_digest 非空）的同摘要快照 */
    Optional<PricingSnapshot> findLiveByDigest(String appId, long userId, String orderNo,
                                               String liveDigest);

    /** 插入快照；pricing_id 由实现生成并回填，撞 uk_live_digest 抛 DuplicateKeyException */
    PricingSnapshot save(PricingSnapshot snapshot);

    /**
     * 锁券绑定：快照未过期则续期到 {@code minExpiresAt} 并记 last_bound_at；
     * 返回 false = 快照已过期/不存在（调用方应 4007 并回滚锁券事务）。
     */
    boolean renewOnLock(String pricingId, LocalDateTime minExpiresAt, LocalDateTime now);

    /** 机会式过期：把已过期仍占着 live_digest 的行置空，允许同摘要重建 */
    boolean clearLiveDigestIfExpired(String pricingId, LocalDateTime now);

    /** 撤销损坏快照：清空 live_digest（不物理删除，保留审计） */
    boolean revoke(String pricingId);

    /** 同摘要重建前：清掉已过期仍占着 live_digest 的行 */
    boolean clearExpiredLiveByDigest(String appId, long userId, String orderNo,
                                     String liveDigest, LocalDateTime now);

    /** 清理第一步：过期行置空 live_digest（保留历史行供审计） */
    int expireLive(LocalDateTime now, int limit);

    /** 清理第二步：删除已过期且 live_digest 为空的历史行（保留期内不动） */
    int deleteExpired(LocalDateTime now, int limit);
}
