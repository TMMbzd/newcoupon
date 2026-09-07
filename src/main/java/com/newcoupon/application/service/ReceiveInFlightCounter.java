package com.newcoupon.application.service;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 领券在途计数器：记录"已过 Redis 闸门、DB 事务尚未终结"的请求数（ADR-0007）。
 *
 * <p>为什么需要它：领券的写序是【先 Redis 后 DB】（ADR-0006）——在途窗口内
 * （Lua 已扣减、DB 未提交），Redis 必然比 total − DB已发 少"在途数"。
 * 对账（StockReconcileService）读这个计数器，把在途量从差异里**精确扣除**——
 * 不然任何流量期间对账都会把"正常时序差"误判为漂移（L21 的根因之一）。
 *
 * <p>把"时序差"从"误差"变成"可扣除的数"，是对账能做精确判断的前提。
 * M2 升级为 Micrometer 指标（多实例可汇总，消化 L16 的多实例口径问题）。
 */
@Component
public class ReceiveInFlightCounter {

    private final AtomicLong count = new AtomicLong();

    /** 在途开始：Lua 扣减成功、进入 DB 阶段时调用 */
    public void inc() {
        count.incrementAndGet();
    }

    /** 在途结束：DB 成功或失败补偿完成后调用（finally 里，任何路径必达） */
    public void dec() {
        count.decrementAndGet();
    }

    /** 当前在途数（对账读取） */
    public long get() {
        return count.get();
    }

    /** 仅测试用：直接设定在途数，模拟并发场景 */
    public void set(long value) {
        count.set(value);
    }
}
