package com.newcoupon.infrastructure.persistence;

import com.newcoupon.domain.pricing.PricingSnapshot;
import com.newcoupon.domain.repository.PricingSnapshotRepository;
import com.newcoupon.infrastructure.persistence.converter.PricingSnapshotPOConverter;
import com.newcoupon.infrastructure.persistence.mapper.CouponPricingSnapshotMapper;
import com.newcoupon.infrastructure.persistence.po.CouponPricingSnapshotPO;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 价格快照仓储适配器（V23）。pricing_id 用 128 位随机串，不可枚举。 */
@Repository
public class PricingSnapshotRepositoryImpl implements PricingSnapshotRepository {

    private final CouponPricingSnapshotMapper mapper;

    public PricingSnapshotRepositoryImpl(CouponPricingSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PricingSnapshot> findByPricingId(String pricingId) {
        return Optional.ofNullable(mapper.selectByPricingId(pricingId))
                .map(PricingSnapshotPOConverter::toDomain);
    }

    @Override
    public Optional<PricingSnapshot> findLiveByDigest(String appId, long userId, String orderNo,
                                                      String liveDigest) {
        return Optional.ofNullable(mapper.selectLiveByDigest(appId, userId, orderNo, liveDigest))
                .map(PricingSnapshotPOConverter::toDomain);
    }

    @Override
    public PricingSnapshot save(PricingSnapshot snapshot) {
        String pricingId = snapshot.pricingId();
        if (pricingId == null || pricingId.isBlank()) {
            pricingId = "P" + UUID.randomUUID().toString().replace("-", "");
        }
        LocalDateTime createTime = snapshot.createTime() == null
                ? LocalDateTime.now() : snapshot.createTime();
        PricingSnapshot toSave = new PricingSnapshot(null, pricingId, snapshot.appId(),
                snapshot.userId(), snapshot.orderNo(), snapshot.couponSet(),
                snapshot.requestDigest(), snapshot.liveDigest(), snapshot.payload(),
                snapshot.payloadVersion(), snapshot.engineVersion(), snapshot.payloadBytes(),
                snapshot.lineCount(), snapshot.appliedCouponCount(), snapshot.originalAmount(),
                snapshot.discountAmount(), snapshot.finalAmount(), snapshot.expiresAt(),
                snapshot.lastBoundAt(), createTime);
        CouponPricingSnapshotPO po = PricingSnapshotPOConverter.toPO(toSave);
        mapper.insert(po);   // 撞 uk_live_digest → DuplicateKeyException，由应用层回读
        return PricingSnapshotPOConverter.toDomain(po);
    }

    @Override
    public boolean renewOnLock(String pricingId, LocalDateTime minExpiresAt, LocalDateTime now) {
        return mapper.renewOnLock(pricingId, minExpiresAt, now) > 0;
    }

    @Override
    public boolean clearLiveDigestIfExpired(String pricingId, LocalDateTime now) {
        return mapper.clearLiveDigestIfExpired(pricingId, now) > 0;
    }

    @Override
    public int expireLive(LocalDateTime now, int limit) {
        return mapper.expireLive(now, limit);
    }

    @Override
    public int deleteExpired(LocalDateTime now, int limit) {
        return mapper.deleteExpired(now, limit);
    }

    @Override
    public boolean clearExpiredLiveByDigest(String appId, long userId, String orderNo,
                                            String liveDigest, LocalDateTime now) {
        return mapper.clearExpiredLiveByDigest(appId, userId, orderNo, liveDigest, now) > 0;
    }

    @Override
    public boolean revoke(String pricingId) {
        return mapper.clearLiveDigest(pricingId) > 0;
    }
}
