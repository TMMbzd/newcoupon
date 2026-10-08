package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处理链的**看门狗**：把"链内异常"变成看得见的事件（迁移第 7 步，2026-10-08）。
 *
 * <p><b>为什么需要它</b>：{@code CtSph.entryWithPriority} 对整条链的调用是这样收尾的——
 * {@code catch (BlockException) → 抛出}，{@code catch (Throwable) → 记一行 RecordLog 然后
 * **照常返回 Entry**（也就是放行）}。也就是说：**链里任何一处内部错误都会静默变成"放行"**，
 * 而它只写进 Sentinel 自己的日志（{@code ~/logs/csp/}），不进应用日志、更没有任何指标。
 *
 * <p><b>为什么"把 SphU.entry 包一层"不管用</b>：异常在 {@code CtSph} 内部就被吞掉了，
 * 调用方拿到的是**一个正常的 Entry**，外面根本 catch 不到（实测复核过）。
 * 唯一能看见它的位置是**链内**——所以在链头放一个看门狗，把下游冒上来的 Throwable 接住。
 *
 * <p><b>行为一个字都不改</b>：接住之后**原样重抛**——拒绝（{@code BlockException}）不碰，
 * 其余异常照旧冒到 {@code CtSph} 被它吞掉并放行。我们只增加"看得见"，不改变"放不放行"。
 * （这一点是硬约束：看门狗自己出错会变成新的静默失效点。）
 *
 * <p><b>为什么不用官方 {@code MetricExtension}</b>：核实过——它看不到任何一条静默失效路径
 * （设计文档 §3.3 原文"用 MetricExtension 打点"是错的），而且**它的实现抛异常会被静默吞掉**，
 * 等于给所有限流加了一个"可能静默全放行"的开关。详见设计文档 §10.6。
 */
public class SentinelWatchdogSlot extends AbstractLinkedProcessorSlot<Object> {

    private static final Logger log = LoggerFactory.getLogger(SentinelWatchdogSlot.class);

    /** 由 Spring 启动时注入（SPI 加载的槽位不受容器管理，只能用静态桥接，同别的自建槽位） */
    private static volatile GovernanceMetrics metrics;

    public static void install(GovernanceMetrics impl) {
        metrics = impl;
    }

    @Override
    public void entry(Context context, ResourceWrapper resource, Object param, int count,
                      boolean prioritized, Object... args) throws Throwable {
        try {
            fireEntry(context, resource, param, count, prioritized, args);
        } catch (BlockException e) {
            throw e;   // 拒绝是正常业务流：原样上抛，一个字不改
        } catch (Throwable t) {
            record(resource, t);
            throw t;   // 原样重抛：外层（CtSph）仍会按它的规则处理——我们只负责让它可见
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper resource, int count, Object... args) {
        try {
            fireExit(context, resource, count, args);
        } catch (Throwable t) {
            // 退出路径的异常同样被吞（而且更难查：它发生在响应已经写出之后）
            record(resource, t);
            throw t;
        }
    }

    private void record(ResourceWrapper resource, Throwable t) {
        GovernanceMetrics impl = metrics;
        if (impl != null) {
            impl.recordSentinelInternalError();
        }
        // **零标签**：这里的维度要么是资源名（有界但我们没做白名单）、要么是异常类型
        // （可能无界）。按项目的基数纪律（GovernanceMetrics 的类注释），
        // 不确定基数就不做标签——具体是谁出问题，看日志。
        log.error("Sentinel 处理链内部异常（resource={}）：按 Sentinel 的语义，这一发会被"
                + "『记一行日志然后放行』处理——也就是说**这次限流判定实际上没生效**。"
                + "指标 coupon_sentinel_internal_error_total 已经在涨，请查根因",
                resource == null ? "?" : resource.getName(), t);
    }
}
