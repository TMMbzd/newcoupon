package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * IP 维度被拒时抛出的异常（迁移第 6 步，2026-10-08）。
 *
 * <p><b>为什么必须是 {@code BlockException} 的子类</b>：与
 * {@link DailyQuotaBlockException} 同一个坑——Slot 里抛业务异常会被
 * {@code CtSph} 的 {@code catch (Throwable)} 当成"slot 自己的内部错误"吞掉并放行。
 * 源码实测（{@code CtSph.entryWithPriority} 的异常表）：
 * <pre>
 *   from 96 to 111, target 114, Class BlockException   → e.exit(...) 后 rethrow
 *   from 96 to 111, target 127, Class Throwable        → 只记 RecordLog 然后**返回 Entry**
 * </pre>
 *
 * <p><b>为什么它不继承 {@code FlowException}</b>：本项目的 IP 维度确实是"限流"，
 * 但 {@code FlowException} 的语义是"**有一条 FlowRule 拒绝了你**"（它有
 * {@code getRule()}，集群流控也复用它）。而 IP 维度的判据来自全站配置 + Redis 计数，
 * **没有规则对象**——继承它就得让 {@code getRule()} 返回 null 或伪造一条规则，
 * 两者都会误导按异常类型分支的代码（包括 Sentinel 自己的工具）。
 * 所以这里直接继承 {@link BlockException}，让"维度"由类型本身表达。
 *
 * <p><b>为什么不带 {@code Retry-After} 字段</b>：{@link DailyQuotaBlockException} 要带，
 * 因为"到明天 0 点"是个变化的值；而 IP 维度的重试间隔恒等于它的窗口长度（1 秒），
 * 由 {@link BlockRejection} 统一给出——异常里再存一份只会多一处可能不一致的地方。
 *
 * <p>{@code ip} 只进异常消息（写日志用），**不进响应体**：响应里回显调用方声明的来源地址
 * 没有任何收益，而日志里有它排障才方便。
 */
public class IpExactBlockException extends BlockException {

    private static final long serialVersionUID = 1L;

    public IpExactBlockException(String ip) {
        super("IP 维度请求速率超限：" + ip);
    }
}
