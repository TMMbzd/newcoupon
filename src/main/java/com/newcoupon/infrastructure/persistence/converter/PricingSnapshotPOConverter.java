package com.newcoupon.infrastructure.persistence.converter;

import com.newcoupon.domain.pricing.PricingSnapshot;
import com.newcoupon.infrastructure.persistence.po.CouponPricingSnapshotPO;

/** 价格快照 PO ↔ 领域记录。payload 原样搬运，解析在应用层。 */
public final class PricingSnapshotPOConverter {

    private PricingSnapshotPOConverter() {
    }

    public static PricingSnapshot toDomain(CouponPricingSnapshotPO po) {
        return new PricingSnapshot(
                po.getId(), po.getPricingId(), po.getAppId(),
                po.getUserId() == null ? 0L : po.getUserId(),
                po.getOrderNo(), po.getCouponSet(), po.getRequestDigest(), po.getLiveDigest(),
                po.getPayload(), po.getPayloadVersion() == null ? 0 : po.getPayloadVersion(),
                po.getEngineVersion(), po.getPayloadBytes() == null ? 0 : po.getPayloadBytes(),
                po.getLineCount() == null ? 0 : po.getLineCount(),
                po.getAppliedCouponCount() == null ? 0 : po.getAppliedCouponCount(),
                po.getOriginalAmount() == null ? 0L : po.getOriginalAmount(),
                po.getDiscountAmount() == null ? 0L : po.getDiscountAmount(),
                po.getFinalAmount() == null ? 0L : po.getFinalAmount(),
                po.getExpiresAt(), po.getLastBoundAt(), po.getCreateTime());
    }

    public static CouponPricingSnapshotPO toPO(PricingSnapshot snapshot) {
        CouponPricingSnapshotPO po = new CouponPricingSnapshotPO();
        po.setId(snapshot.id());
        po.setPricingId(snapshot.pricingId());
        po.setAppId(snapshot.appId());
        po.setUserId(snapshot.userId());
        po.setOrderNo(snapshot.orderNo());
        po.setCouponSet(snapshot.couponSet());
        po.setRequestDigest(snapshot.requestDigest());
        po.setLiveDigest(snapshot.liveDigest());
        po.setPayload(snapshot.payload());
        po.setPayloadVersion(snapshot.payloadVersion());
        po.setEngineVersion(snapshot.engineVersion());
        po.setPayloadBytes(snapshot.payloadBytes());
        po.setLineCount(snapshot.lineCount());
        po.setAppliedCouponCount(snapshot.appliedCouponCount());
        po.setOriginalAmount(snapshot.originalAmount());
        po.setDiscountAmount(snapshot.discountAmount());
        po.setFinalAmount(snapshot.finalAmount());
        po.setExpiresAt(snapshot.expiresAt());
        po.setLastBoundAt(snapshot.lastBoundAt());
        po.setCreateTime(snapshot.createTime());
        return po;
    }
}
