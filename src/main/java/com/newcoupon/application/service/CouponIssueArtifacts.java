package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.infrastructure.persistence.po.CouponOutboxPO;
import java.time.LocalDateTime;

/**
 * 领券时同事务写入的派生对象工厂。
 *
 * <p>单任务路径和同分片批量路径必须生成**逐字节相同**的 outbox payload；把格式放在这里，
 * 避免两条路径各写一份字符串后悄悄漂移。payload 的消费方以 eventNo 做幂等锚点。
 */
public final class CouponIssueArtifacts {

    private CouponIssueArtifacts() {
    }

    public static CouponOutboxPO issuedOutbox(CouponEvent event, UserCoupon coupon, LocalDateTime now) {
        CouponOutboxPO outbox = new CouponOutboxPO();
        outbox.setEventNo(event.getEventNo());
        outbox.setEventType(event.getEventType().getCode());
        outbox.setPayload("{\"eventNo\":\"" + event.getEventNo()
                + "\",\"type\":\"" + event.getEventType().name()
                + "\",\"userCouponId\":" + coupon.getId()
                + ",\"templateId\":" + coupon.getTemplateId()
                + ",\"userId\":" + coupon.getUserId()
                + ",\"requestId\":\"" + coupon.getIdempotentKey()
                + "\",\"occurredAt\":\"" + now + "\"}");
        outbox.setStatus(CouponOutboxPO.STATUS_PENDING);
        outbox.setRetryCount(0);
        outbox.setNextScanTime(now);
        return outbox;
    }
}
