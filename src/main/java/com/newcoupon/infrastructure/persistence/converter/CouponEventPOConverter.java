package com.newcoupon.infrastructure.persistence.converter;

import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.EventType;
import com.newcoupon.infrastructure.persistence.po.CouponEventPO;

/** CouponEvent DO ↔ PO 转换（事件结构简单，仅搬运 + 枚举映射） */
public final class CouponEventPOConverter {

    private CouponEventPOConverter() {
    }

    public static CouponEventPO toPO(CouponEvent d) {
        CouponEventPO p = new CouponEventPO();
        p.setId(d.getId());
        p.setEventNo(d.getEventNo());
        p.setEventType(d.getEventType().getCode());
        p.setTemplateId(d.getTemplateId());
        p.setUserCouponId(d.getUserCouponId());
        p.setUserId(d.getUserId());
        p.setOrderNo(d.getOrderNo());
        p.setAmount(d.getAmount());
        p.setOccurTime(d.getOccurTime());
        return p;
    }

    public static CouponEvent toDomain(CouponEventPO p) {
        return CouponEvent.reconstitute(p.getId(), p.getEventNo(), EventType.of(p.getEventType()),
                p.getTemplateId(), p.getUserCouponId(), p.getUserId(),
                p.getOrderNo(), p.getAmount(), p.getOccurTime());
    }
}
