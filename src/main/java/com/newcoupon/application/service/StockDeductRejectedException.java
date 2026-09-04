package com.newcoupon.application.service;

/**
 * 扣库存被拒（条件 UPDATE 影响行数=0）的内部信号异常。
 *
 * <p>为什么不用 BizException 直接抛 STOCK_OUT：0 行的真实成因有两种（真售罄/模板状态竞态），
 * 而**在事务内无法可靠区分**——MySQL 可重复读(RR)隔离级别下，事务内的普通 SELECT
 * 读的是【事务首次读时建立的快照】，不是最新已提交数据（UPDATE 才是"当前读"）。
 * 竞态线程的快照建立于库存未耗尽时，事务内重读永远看到旧值，会把"售罄"误分类成"被下线"。
 * （2026-09-05 集成测试实测抓到：1000 并发中 46 个请求被误报 2002。）
 *
 * <p>因此本异常只表示"扣减被拒，请到事务外用新快照重查分类"——
 * 编排层接住后在无事务上下文里重读模板（新连接=新快照），拿到真实的 issued_count 分类。
 */
public class StockDeductRejectedException extends RuntimeException {

    public StockDeductRejectedException(long templateId) {
        super("stock deduct rejected (0 row): templateId=" + templateId);
    }
}
