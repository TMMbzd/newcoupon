package com.newcoupon.domain.coupon;

import java.time.LocalDateTime;

/**
 * 券事件（事实源的一条记录）：领取/锁定/核销/释放/过期/作废全部落事件，
 * 统计与对账从事件表回放（docs/01 第 4.1 节"事件明细是唯一事实源"）。
 *
 * <p>已产生的事件：发放(issued)/锁定(locked)/核销(used,含金额)/释放(released)。
 * 尚无产生点：过期(EXPIRED)/作废(INVALIDATED)——随 M1 过期全链路与 M6 作废功能补齐。
 */
public final class CouponEvent {

    private final Long id;
    private final String eventNo;
    private final EventType eventType;
    private final long templateId;
    private final long userCouponId;
    private final long userId;
    private final String orderNo;
    private final Long amount;
    private final LocalDateTime occurTime;

    private CouponEvent(Long id, String eventNo, EventType eventType, long templateId,
                        long userCouponId, long userId, String orderNo, Long amount,
                        LocalDateTime occurTime) {
        this.id = id;
        this.eventNo = eventNo;
        this.eventType = eventType;
        this.templateId = templateId;
        this.userCouponId = userCouponId;
        this.userId = userId;
        this.orderNo = orderNo;
        this.amount = amount;
        this.occurTime = occurTime;
    }

    /**
     * 发放事件。
     *
     * <p>event_no 生成规则（ADR-0004 增补3）：{@code "RCV:" + userId + ":" + requestId}——
     * 作用域与领券幂等键 (user_id, idempotent_key) 对齐：同一用户重放同一请求生成同一编号，
     * 被 uk_event_no 唯一索引撞掉（事件不可被重放污染）；不同用户撞了相同 requestId 也不会误撞编号。
     *
     * <p>amount 口径：发放事件不填（NULL）——面额不是实际抵扣额，核销事件才记真实抵扣金额（docs/01 4.2 第5表注释）。
     * 发放事件的 amount 若填 face_value，对账时"优惠总成本"会被未核销的券虚增。
     */
    public static CouponEvent issued(UserCoupon coupon, LocalDateTime now) {
        return new CouponEvent(null,
                "RCV:" + coupon.getUserId() + ":" + coupon.getIdempotentKey(),
                EventType.ISSUED, coupon.getTemplateId(), coupon.getId(), coupon.getUserId(),
                null, null, now);
    }

    /**
     * 锁定事件。event_no 用 "LCK:"+usageNo——usage_no 本身全表唯一（订单号+券id），
     * 天然防重放；templateId 取自券（对账按模板维度聚合时依赖它，不能填占位值）。
     */
    public static CouponEvent locked(UserCoupon coupon, CouponUsage usage, LocalDateTime now) {
        return new CouponEvent(null,
                "LCK:" + usage.getUsageNo(), EventType.LOCKED,
                coupon.getTemplateId(), usage.getUserCouponId(), usage.getUserId(),
                usage.getOrderNo(), null, now);
    }

    /** 核销事件：amount = 本单实际抵扣金额（财务对账的金额事实源） */
    public static CouponEvent used(UserCoupon coupon, CouponUsage usage, long discountAmt, LocalDateTime now) {
        return new CouponEvent(null,
                "USE:" + usage.getUsageNo(), EventType.USED,
                coupon.getTemplateId(), usage.getUserCouponId(), usage.getUserId(),
                usage.getOrderNo(), discountAmt, now);
    }

    /** 释放回退事件 */
    public static CouponEvent released(UserCoupon coupon, CouponUsage usage, LocalDateTime now) {
        return new CouponEvent(null,
                "RLS:" + usage.getUsageNo(), EventType.RELEASED,
                coupon.getTemplateId(), usage.getUserCouponId(), usage.getUserId(),
                usage.getOrderNo(), null, now);
    }

    /** 从数据库复活 */
    public static CouponEvent reconstitute(Long id, String eventNo, EventType eventType, long templateId,
                                           long userCouponId, long userId, String orderNo,
                                           Long amount, LocalDateTime occurTime) {
        return new CouponEvent(id, eventNo, eventType, templateId, userCouponId, userId,
                orderNo, amount, occurTime);
    }

    public Long getId() { return id; }
    public String getEventNo() { return eventNo; }
    public EventType getEventType() { return eventType; }
    public long getTemplateId() { return templateId; }
    public long getUserCouponId() { return userCouponId; }
    public long getUserId() { return userId; }
    public String getOrderNo() { return orderNo; }
    public Long getAmount() { return amount; }
    public LocalDateTime getOccurTime() { return occurTime; }
}
