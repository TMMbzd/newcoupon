package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * 日配额被拒时抛出的异常（迁移第 5 步）。
 *
 * <p><b>为什么必须是 {@code BlockException} 的子类——这里踩了一个很隐蔽的坑</b>：
 * Slot 一开始抛的是项目自己的 {@code BizException}，结果**请求畅通无阻**。
 * 排查过程：日志显示 Slot 已执行、资源名对、计数键的值也从 1 涨到 2，
 * 但就是不放拒绝——最后才定位到 {@code CtSph.entryWithPriority} 里有一段
 * {@code catch (Throwable)}，**把 Slot 抛出的非 BlockException 当作"slot 自己的内部错误"
 * 吞掉并放行**（只在 Sentinel 自己的日志里留痕）。
 *
 * <p>也就是说：**在 Slot 里抛业务异常 = 静默放行**。要真正拒绝，必须抛
 * {@link BlockException} 的子类——这是 Sentinel 的约定，但它不在任何显眼的位置写着。
 *
 * <p>抛出去之后由谁处理：我们的埋点是**手写的**（{@code OpenApiQuotaFilter} 里的
 * {@code SphU.entry}），所以会走到我们自己的 catch，再转成 {@code BizException}
 * 交给全局异常处理器——于是"日配额被拒"与"限流被拒"的响应形态完全一致。
 */
public class DailyQuotaBlockException extends BlockException {

    private static final long serialVersionUID = 1L;

    /** 到明天 0 点的秒数——给拒绝响应里的 {@code Retry-After} 用 */
    private final long retryAfterSeconds;

    public DailyQuotaBlockException(String appId, long retryAfterSeconds) {
        super("日配额已用尽：" + appId);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
