package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.CtSph;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Sentinel **自身状态**的两条 gauge（迁移第 7 步，2026-10-08）。
 *
 * <p><b>为什么是这两条</b>：Sentinel 有几条"静默放行"路径（详见设计文档 §3.3 的清单，
 * 第 7 步又补了第六条）。逐条核实"能不能观测"之后，结论是**只有这两条能零成本地做出来**，
 * 而且恰好是最致命的两条——它们一发生，**所有规则全部失效**且没有任何征兆：
 *
 * <ol>
 *   <li>{@code coupon_sentinel_enabled} = {@code Constants.ON}。
 *       {@code ON=false} 时 {@code CtSph} **连处理链都不建**（我们的自建槽位自然也一个都不执行），
 *       所有请求直接放行。它是 {@code public static volatile} 字段，读它零成本。
 *       框架里唯一能改它的是官方 transport 的 {@code setSwitch} 命令，而本项目**没有引 transport**
 *       ——所以今天没人能翻这个开关；正因为如此，它更像一个"某天接上控制台后突然被关掉"的隐患，
 *       值得有一条恒为 1 的序列盯着（**=0 就该立刻告警**）。</li>
 *   <li>{@code coupon_sentinel_slot_chain_count} = {@code CtSph.entrySize()}。
 *       Sentinel 对"处理链数量"有硬上限（{@code MAX_SLOT_CHAIN_SIZE = 6000}），
 *       超了之后**新资源直接拿不到链**：完全不检查、不统计、不报错、连一行日志都没有
 *       （核实时实测：填满 6000 后新增资源的事件增量为 0）。
 *       而链是按**资源名**建的——资源名基数爆炸（比如把路径参数拼进资源名）正是会走到那里的路。
 *       本项目正常值应是"控制器映射数 + 2"这个量级，所以**阈值不要贴着 6000**，
 *       几百就该告警：它只会因为"资源名基数在涨"而涨，而那本身就是 bug。</li>
 * </ol>
 *
 * <p><b>为什么用 Micrometer 的 {@code Gauge.builder(name, Supplier)} 重载</b>：
 * 另一个重载（{@code Gauge.builder(name, obj, ToDoubleFunction)}）**弱引用**被观测对象——
 * 对象一旦被 GC，指标就变成 NaN（一个很经典的坑）。这里观测的是静态字段/静态方法，
 * 用 Supplier 形式既没有生命周期问题，也不必"预热"。
 *
 * <p><b>为什么放在 {@code @Configuration} 的构造器里</b>：与
 * {@code CircuitBreakerMetricsConfig} 同一个理由——避免 {@code MeterRegistry → MeterBinder → ...}
 * 的创建环（那次踩过，见那个类的注释）。
 */
@Configuration
public class SentinelSelfMetrics {

    public SentinelSelfMetrics(MeterRegistry registry) {
        Gauge.builder("coupon_sentinel_enabled", () -> Constants.ON ? 1 : 0)
                .description("Sentinel 全局开关（0=已关闭，此时所有规则静默失效、请求全部放行；"
                        + "正常应恒为 1，=0 立即告警）")
                .register(registry);

        Gauge.builder("coupon_sentinel_slot_chain_count", CtSph::entrySize)
                .description("Sentinel 已建的处理链数量（按资源名建；上限 6000，超了之后新资源"
                        + "静默不受任何检查。正常是『控制器映射数 + 2』的量级）")
                .register(registry);
    }
}
