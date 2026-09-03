package com.newcoupon.domain.statemachine;

import com.newcoupon.domain.coupon.CouponStatus;

/**
 * 券状态机的合法迁移定义——与 docs/01 第 4.3 节"合法迁移表"一一对应。
 *
 * <p>设计意图：把"哪些流转是合法的"从散落各处的 if-else 收敛到**这一张枚举表**里。
 * 新增流转 = 加一行枚举 + 一行单测；非法流转 = 单测里天然没有对应行，
 * 任何想绕过状态机直接改 status 的代码，在评审时对着这张表一秒识破。
 */
public enum CouponTransition {

    /** 领券/发券成功 */
    RECEIVE(null, CouponStatus.USABLE, "领券成功"),
    /** 下单锁券（占用开始） */
    LOCK(CouponStatus.USABLE, CouponStatus.LOCKED, "下单锁券"),
    /** 支付成功核销（券的死亡，终态） */
    CONFIRM(CouponStatus.LOCKED, CouponStatus.USED, "支付成功核销"),
    /** 订单取消/支付超时释放（由占用单状态驱动，未过期则回可用） */
    RELEASE(CouponStatus.LOCKED, CouponStatus.USABLE, "订单取消释放"),
    /** 锁定期超时仍未核销 */
    EXPIRE_ON_LOCK(CouponStatus.LOCKED, CouponStatus.EXPIRED, "锁定期超时过期"),
    /** 到期未使用（定时任务+查询惰性判断双保险） */
    EXPIRE(CouponStatus.USABLE, CouponStatus.EXPIRED, "到期未使用"),
    /** 风控冻结/运营作废 */
    INVALIDATE(CouponStatus.USABLE, CouponStatus.INVALID, "作废"),
    /** 锁定中被作废 */
    INVALIDATE_ON_LOCK(CouponStatus.LOCKED, CouponStatus.INVALID, "锁定中作废");

    private final CouponStatus from;
    private final CouponStatus to;
    private final String trigger;

    CouponTransition(CouponStatus from, CouponStatus to, String trigger) {
        this.from = from;
        this.to = to;
        this.trigger = trigger;
    }

    public CouponStatus getFrom() {
        return from;
    }

    public CouponStatus getTo() {
        return to;
    }

    public String getTrigger() {
        return trigger;
    }
}
