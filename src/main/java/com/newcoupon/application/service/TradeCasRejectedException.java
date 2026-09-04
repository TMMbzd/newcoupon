package com.newcoupon.application.service;

/**
 * 状态迁移 CAS（条件 UPDATE）影响 0 行的内部信号——与 StockDeductRejectedException 同一纪律：
 * 事务内不做分类（RR 快照读会读到旧照片，study/04a 第 3.2 节的教训），
 * 抛出异常让事务回滚，编排层用事务外的新快照重查、给出准确的错误信息。
 */
public class TradeCasRejectedException extends RuntimeException {

    /** 动作标识（lock/usage-confirm/coupon-confirm/...），仅供日志与排障 */
    private final String action;

    public TradeCasRejectedException(String action, Object target) {
        super("trade cas rejected: action=" + action + ", target=" + target);
        this.action = action;
    }

    public String getAction() {
        return action;
    }
}
