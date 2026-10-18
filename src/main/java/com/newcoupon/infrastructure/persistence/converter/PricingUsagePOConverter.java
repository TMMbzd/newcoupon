package com.newcoupon.infrastructure.persistence.converter;

import com.newcoupon.domain.pricing.PricingUsage;
import com.newcoupon.infrastructure.persistence.po.CouponPricingUsagePO;

/** 占用单 ↔ 价格快照绑定 PO 转换。 */
public final class PricingUsagePOConverter {

    private PricingUsagePOConverter() {
    }

    public static PricingUsage toDomain(CouponPricingUsagePO po) {
        return new PricingUsage(po.getId(), po.getUsageNo(), po.getPricingId(), po.getAppId(),
                po.getUserId() == null ? 0L : po.getUserId(), po.getOrderNo(),
                po.getCouponId() == null ? 0L : po.getCouponId(), po.getBoundAt());
    }

    public static CouponPricingUsagePO toPO(PricingUsage usage) {
        CouponPricingUsagePO po = new CouponPricingUsagePO();
        po.setId(usage.id());
        po.setUsageNo(usage.usageNo());
        po.setPricingId(usage.pricingId());
        po.setAppId(usage.appId());
        po.setUserId(usage.userId());
        po.setOrderNo(usage.orderNo());
        po.setCouponId(usage.couponId());
        po.setBoundAt(usage.boundAt());
        return po;
    }
}
