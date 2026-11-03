package com.newcoupon.domain.coupon;

import com.newcoupon.common.BoundedKey;

import java.time.LocalDateTime;

/**
 * 券事件（事实源的一条记录）：领取/锁定/核销/释放/过期/作废全部落事件，
 * 统计与对账从事件表回放（docs/01 第 4.1 节"事件明细是唯一事实源"）。
 *
 * <p>已产生的事件：发放/锁定/核销/释放/过期/锁定超时/退款/核销异常。
 * 尚无产生点：作废(INVALIDATED)——随 M6 作废功能补齐。
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
    private final String appId;
    private final Long activityId;
    private final String source;
    private final String bizType;
    private final String bizId;

    private CouponEvent(Long id, String eventNo, EventType eventType, long templateId,
                        long userCouponId, long userId, String orderNo, Long amount,
                        LocalDateTime occurTime, BizContext biz) {
        this.id = id;
        this.eventNo = eventNo;
        this.eventType = eventType;
        this.templateId = templateId;
        this.userCouponId = userCouponId;
        this.userId = userId;
        this.orderNo = orderNo;
        this.amount = amount;
        this.occurTime = occurTime;
        // biz 为 null 表示这条事件没有业务上下文（非发放事件/旧调用点）：五列全落 NULL
        this.appId = biz == null ? null : biz.appId();
        this.activityId = biz == null ? null : biz.activityId();
        this.source = biz == null ? null : biz.source();
        this.bizType = biz == null ? null : biz.bizType();
        this.bizId = biz == null ? null : biz.bizId();
    }

    /**
     * 发放事件的业务上下文（V6 阶段 1D 验收 #7，V43 五列的内存形态）。
     *
     * <p>全部字段可空：这是可观测性归因，不是行为判据——某条链路没填，事件照常落库、
     * 对应列落 NULL，存量调用零感知。appId/activityId 从模板行冗余（写时快照，防模板
     * 后改导致历史事件"变性"）；source/bizType/bizId 描述"这次发放从哪来、挂在哪张单上"。
     */
    public record BizContext(String appId, Long activityId, String source,
                             String bizType, String bizId) {

        /** 发放链路：同步领取（HTTP 入口直接发） */
        public static final String SOURCE_SYNC = "SYNC";
        /** 发放链路：异步受理任务（先收单后由 worker 落库） */
        public static final String SOURCE_ASYNC = "ASYNC";
        /** 发放链路：批量发放任务 worker */
        public static final String SOURCE_GRANT = "GRANT";
        /** 发放链路：兑换码兑换 */
        public static final String SOURCE_CODE = "CODE";
        /** 单据背景：批量发放任务（bizId=taskNo） */
        public static final String BIZ_TYPE_GRANT_TASK = "GRANT_TASK";
        /** 单据背景：兑换码批次（bizId=batchNo） */
        public static final String BIZ_TYPE_CODE = "CODE";
    }

    /**
     * 发放事件。
     *
     * <p>event_no 生成规则（ADR-0004 增补3 + V28）：{@code "RCV:" + appId + ":" + userId + ":" + requestId}——
     * 作用域与 V28 的领券幂等键 (user_id, app_id, idempotent_key) 对齐：同一租户内重放同一请求
     * 生成同一编号，被 uk_event_no 唯一索引撞掉；同一 user+requestId 在两个租户下是不同请求，
     * 必须生成不同编号，否则第二家的发放事务会撞事件唯一键并整体回滚。
     *
     * <p>amount 口径：发放事件不填（NULL）——面额不是实际抵扣额，核销事件才记真实抵扣金额（docs/01 4.2 第5表注释）。
     * 发放事件的 amount 若填 face_value，对账时"优惠总成本"会被未核销的券虚增。
     */
    public static CouponEvent issued(UserCoupon coupon, LocalDateTime now) {
        // 兼容旧调用点：不带业务上下文（V43 五列全落 NULL）
        return issued(coupon, now, null);
    }

    /**
     * 带业务上下文的发放事件（V6 阶段 1D）：appId/activityId 从模板冗余、source/biz
     * 描述链路与单据背景——写时快照进事件行，之后模板怎么改都不影响历史事件的归因。
     */
    public static CouponEvent issued(UserCoupon coupon, LocalDateTime now, BizContext biz) {
        return new CouponEvent(null,
                BoundedKey.of("RCV:", coupon.getAppId() + ":" + coupon.getUserId()
                        + ":" + coupon.getIdempotentKey()),
                EventType.ISSUED, coupon.getTemplateId(), coupon.getId(), coupon.getUserId(),
                null, null, now, biz);
    }

    /**
     * 过期事件（L2，2026-09-12）。event_no = "EXP:"+券id——券只会过期一次（EXPIRED 是终态），
     * 天然防重放。usage 可空：自然到期（任务表扫描）没有占用单；锁定期间过期（核销被拒的
     * 收场路径）带占用单，order_no 记订单号供对账归因。
     */
    public static CouponEvent expired(UserCoupon coupon, CouponUsage usage, LocalDateTime now) {
        return new CouponEvent(null,
                BoundedKey.of("EXP:", String.valueOf(coupon.getId())),
                EventType.EXPIRED, coupon.getTemplateId(), coupon.getId(), coupon.getUserId(),
                usage == null ? null : usage.getOrderNo(), null, now, null);
    }

    /**
     * 锁定事件。event_no 用 "LCK:"+usageNo——usage_no 本身全表唯一（订单号+券id），
     * 天然防重放；templateId 取自券（对账按模板维度聚合时依赖它，不能填占位值）。
     */
    public static CouponEvent locked(UserCoupon coupon, CouponUsage usage, LocalDateTime now) {
        return new CouponEvent(null,
                BoundedKey.of("LCK:", usage.getUsageNo()), EventType.LOCKED,
                coupon.getTemplateId(), usage.getUserCouponId(), usage.getUserId(),
                usage.getOrderNo(), null, now, null);
    }

    /** 核销事件：amount = 本单实际抵扣金额（财务对账的金额事实源） */
    public static CouponEvent used(UserCoupon coupon, CouponUsage usage, long discountAmt, LocalDateTime now) {
        return new CouponEvent(null,
                BoundedKey.of("USE:", usage.getUsageNo()), EventType.USED,
                coupon.getTemplateId(), usage.getUserCouponId(), usage.getUserId(),
                usage.getOrderNo(), discountAmt, now, null);
    }

    /** 退款冲销事件（第 2 环，兼容整单退：事件号按 usageNo，天然一次性） */
    public static CouponEvent refund(UserCoupon coupon, CouponUsage usage, long discountAmt, LocalDateTime now) {
        return refund(coupon, usage, usage.getUsageNo(), discountAmt, now);
    }

    /**
     * 行级退款事件（2026-10-18）：一张占用单可以退多次，事件号必须按 refundNo 唯一，
     * 不能再用 usageNo（第二次部分退款会撞 uk_event_no）。
     */
    public static CouponEvent refund(UserCoupon coupon, CouponUsage usage, String refundNo,
                                     long discountAmt, LocalDateTime now) {
        return new CouponEvent(null,
                BoundedKey.of("RFD:", refundNo), EventType.REFUNDED,
                coupon.getTemplateId(), usage.getUserCouponId(), usage.getUserId(),
                usage.getOrderNo(), discountAmt, now, null);
    }

    /**
     * 核销异常事件（环 2）：用户已支付但带 pricingId 的核销无法完成。
     * event_no = "CEX:"+usageNo——占用单只会进入异常一次（CAS 0→7 保证）。
     */
    public static CouponEvent confirmException(UserCoupon coupon, CouponUsage usage, LocalDateTime now) {
        return new CouponEvent(null,
                BoundedKey.of("CEX:", usage.getUsageNo()), EventType.CONFIRM_EXCEPTION,
                coupon.getTemplateId(), usage.getUserCouponId(), usage.getUserId(),
                usage.getOrderNo(), null, now, null);
    }

    /** 释放回退事件 */
    public static CouponEvent released(UserCoupon coupon, CouponUsage usage, LocalDateTime now) {
        return new CouponEvent(null,
                BoundedKey.of("RLS:", usage.getUsageNo()), EventType.RELEASED,
                coupon.getTemplateId(), usage.getUserCouponId(), usage.getUserId(),
                usage.getOrderNo(), null, now, null);
    }

    /**
     * 锁定超时释放事件（L5，2026-09-15）。event_no = "LKT:"+usageNo——usage_no 全表唯一，
     * 天然防重放。注意不能用券id当编号：一张券一生可签多份合同（锁定→释放→再锁定），
     * 每份合同的超时收场都是一条独立事件。orderNo 记订单供对账归因"哪个订单的死锁被收了场"。
     */
    public static CouponEvent lockTimeout(UserCoupon coupon, CouponUsage usage, LocalDateTime now) {
        return new CouponEvent(null,
                BoundedKey.of("LKT:", usage.getUsageNo()), EventType.LOCK_TIMEOUT,
                coupon.getTemplateId(), coupon.getId(), usage.getUserId(),
                usage.getOrderNo(), null, now, null);
    }

    /** 从数据库复活（旧签名：业务上下文列不回读，兼容既有调用点） */
    public static CouponEvent reconstitute(Long id, String eventNo, EventType eventType, long templateId,
                                           long userCouponId, long userId, String orderNo,
                                           Long amount, LocalDateTime occurTime) {
        return reconstitute(id, eventNo, eventType, templateId, userCouponId, userId,
                orderNo, amount, occurTime, null);
    }

    /** 从数据库复活（V43 全参版：连业务上下文五列一起回读） */
    public static CouponEvent reconstitute(Long id, String eventNo, EventType eventType, long templateId,
                                           long userCouponId, long userId, String orderNo,
                                           Long amount, LocalDateTime occurTime, BizContext biz) {
        return new CouponEvent(id, eventNo, eventType, templateId, userCouponId, userId,
                orderNo, amount, occurTime, biz);
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
    public String getAppId() { return appId; }
    public Long getActivityId() { return activityId; }
    public String getSource() { return source; }
    public String getBizType() { return bizType; }
    public String getBizId() { return bizId; }
}
