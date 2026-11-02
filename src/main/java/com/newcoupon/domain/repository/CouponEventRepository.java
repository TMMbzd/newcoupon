package com.newcoupon.domain.repository;

import com.newcoupon.domain.coupon.CouponEvent;

/**
 * 券事件仓储端口。事件只追加（append-only）：不提供更新/删除——
 * 事实源一旦允许改写，对账就失去了意义（docs/01 第 4.1 节）。
 * 唯一索引 uk_event_no 保证重放/补偿产生的重复事件被数据库撞掉。
 */
public interface CouponEventRepository {

    /** 追加一条事件（与业务写入同事务，见 CouponIssueTxService） */
    void append(CouponEvent event);

    /**
     * 某模板从某时刻起已发放的事件数（对账/历史统计用）。
     *
     * <p><b>不在领取热路径</b>：热路径用 V27 {@code coupon_daily_counter} 条件自增。
     * 选事件表而不是 user_coupon 是历史原因：事件表是单表且已有
     * {@code (template_id, occur_time)} 索引，按 template_id 统计不会广播 64 张用户分表。
     */
    long countIssuedByTemplateSince(long templateId, java.time.LocalDateTime since);
}
