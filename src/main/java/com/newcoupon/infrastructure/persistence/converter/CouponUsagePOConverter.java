package com.newcoupon.infrastructure.persistence.converter;

import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.infrastructure.persistence.po.CouponUsagePO;

/** CouponUsage DO ↔ PO 转换 */
public final class CouponUsagePOConverter {

    private CouponUsagePOConverter() {
    }

    public static CouponUsagePO toPO(CouponUsage d) {
        CouponUsagePO p = new CouponUsagePO();
        p.setId(d.getId());
        p.setUsageNo(d.getUsageNo());
        p.setUserId(d.getUserId());
        p.setUserCouponId(d.getUserCouponId());
        p.setOrderNo(d.getOrderNo());
        p.setStatus(d.getStatus().getCode());
        p.setDiscountAmt(d.getDiscountAmt());
        p.setOccupyTime(d.getOccupyTime());
        p.setUseTime(d.getUseTime());
        p.setReleaseTime(d.getReleaseTime());
        return p;
    }

    public static CouponUsage toDomain(CouponUsagePO p) {
        return CouponUsage.reconstitute(p.getId(), p.getUsageNo(), p.getUserId(),
                p.getUserCouponId(), p.getOrderNo(), UsageStatus.of(p.getStatus()),
                p.getDiscountAmt(), p.getOccupyTime(), p.getUseTime(), p.getReleaseTime());
    }
}
