package com.newcoupon.infrastructure.persistence.converter;

import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.infrastructure.persistence.po.UserCouponPO;
import java.time.LocalDateTime;

/**
 * UserCoupon DO ↔ PO 转换。toDomain 走 reconstitute（复活），状态以 DB 为准——
 * 阶段 0 只有 USABLE 一种落库状态，后续用例扩展状态后此纪律更关键（防止 03a 讲过的"状态重置"bug）。
 */
public final class UserCouponPOConverter {

    private UserCouponPOConverter() {
    }

    public static UserCouponPO toPO(UserCoupon d, LocalDateTime receiveTime) {
        UserCouponPO p = new UserCouponPO();
        p.setId(d.getId());
        p.setUserId(d.getUserId());
        p.setTemplateId(d.getTemplateId());
        p.setTemplateCode(d.getTemplateCode());
        p.setFaceValue(d.getFaceValue());
        p.setThreshold(d.getThreshold());
        p.setDiscountCap(d.getDiscountCap());
        p.setValidEnd(d.getValidEnd());
        p.setStatus(d.getStatus().getCode());
        p.setReceiveTime(receiveTime);
        p.setIdempotentKey(d.getIdempotentKey());
        p.setSeq(d.getSeq());
        return p;
    }

    public static UserCoupon toDomain(UserCouponPO p) {
        return UserCoupon.reconstitute(
                p.getId(), p.getUserId(), p.getTemplateId(), p.getTemplateCode(),
                p.getFaceValue(), p.getThreshold(), p.getDiscountCap(),
                p.getValidEnd(), CouponStatus.of(p.getStatus()),
                p.getReceiveTime(), p.getIdempotentKey(), p.getSeq());
    }
}
