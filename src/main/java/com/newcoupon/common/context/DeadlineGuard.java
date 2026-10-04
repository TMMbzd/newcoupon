package com.newcoupon.common.context;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;

/**
 * 时间预算的裁剪判据（S2 第 9 步）——**把"要不要继续干活"的决策收在一处**。
 *
 * <p><b>三条不可让步的原则</b>（每一条都有代价惨重的反例，见设计文档 §4.5）：
 * <ol>
 *   <li><b>只做"要不要开始"，不做"做到一半掐断"</b>——同步 Servlet 栈没有安全的线程抢占，
 *       而且打断一个已经发出的 Redis 写命令会留下**无法补偿的孤儿状态**
 *       （库存扣了、令牌入队了，而调用方拿不到补偿所需的句柄）；</li>
 *   <li><b>默认不可跳</b>——领券链路上的"读"大多承担自愈职责（计数复核、假售罄修复），
 *       跳过它们会把"本可自愈成功"变成"假拒绝"。可跳的只有明确的例外（如 confirm 丢失有对账兜底）；</li>
 *   <li><b>先问 {@link RequestContext#hasDeadline()}</b>——内部面与定时任务没有预算，
 *       不能拿 {@link Long#MAX_VALUE} 参与算术。</li>
 * </ol>
 */
public final class DeadlineGuard {

    /**
     * "还能再重试一次"的地板：一次 Redis 调用的全局超时（1 秒）+ 退避上界（200 毫秒）。
     *
     * <p>低于这个值还去重试，等于明知会超时还给已经放弃的请求再等一秒。
     */
    public static final long RETRY_FLOOR_MS = 1_200;

    /**
     * "还能开始一次写事务"的地板。
     *
     * <p>理由与重试地板不同：写事务**一旦开始就必须做完**（不能中断），
     * 所以判断的是"还够不够做完"，而不是"还够不够试一次"。不够就**宁可不开始**。
     */
    public static final long WRITE_FLOOR_MS = 500;

    private DeadlineGuard() {
    }

    /** 预算是否已经用完（无预算的场景永远返回 false） */
    public static boolean expired() {
        return expired(RequestContext.current(), System.currentTimeMillis());
    }

    /**
     * 判据本体（由调用方提供"现在"）。
     *
     * <p><b>为什么要这个形态</b>：边界（剩余 0 / 地板值 / 负数 / 哨兵）如果只能在集成测试里
     * 靠墙钟验证，就永远在和线程调度赛跑——单测传固定值、生产传 {@code System.currentTimeMillis()}，
     * 边界才第一次成为"可以钉死的事实"。
     */
    public static boolean expired(RequestContext ctx, long nowMs) {
        return ctx.hasDeadline() && ctx.remainingMs(nowMs) <= 0;
    }

    /** 剩余预算是否已低于某个地板（无预算的场景永远返回 false） */
    public static boolean belowFloor(long floorMs) {
        return belowFloor(RequestContext.current(), System.currentTimeMillis(), floorMs);
    }

    /** 判据本体（由调用方提供"现在"）；比较是**严格小于**：恰好等于地板值时放行 */
    public static boolean belowFloor(RequestContext ctx, long nowMs, long floorMs) {
        return ctx.hasDeadline() && ctx.remainingMs(nowMs) < floorMs;
    }

    /**
     * 准入检查：预算已耗尽就抛出可重试的业务码。
     *
     * <p>调用点必须放在**身份与归属判定之后**——否则越权请求会被洗成"预算耗尽"，
     * 既错误归因，也让审计丢掉"有人在探测"的信号。
     */
    public static void requireBudget() {
        if (expired()) {
            throw new BizException(ErrorCode.REQUEST_BUDGET_EXHAUSTED);
        }
    }
}
