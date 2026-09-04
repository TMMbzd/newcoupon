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
}
