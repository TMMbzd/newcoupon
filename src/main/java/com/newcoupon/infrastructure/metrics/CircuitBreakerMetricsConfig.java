package com.newcoupon.infrastructure.metrics;

import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * 熔断器指标接入 Micrometer（S2 第 11 步，RQ-10）。
 *
 * <p><b>为什么用官方 {@code TaggedCircuitBreakerMetrics}</b>：它会订阅 registry 的
 * 条目增删事件——先绑定后创建、先创建后绑定、按租户动态创建、{@code evictTenant} 移除，
 * 四种时序都自动跟进（已用探针实测）。自建 Gauge 要把这套生命周期再实现一遍，
 * 而且几乎一定会漏掉"后创建的熔断器"（那正是第 10 步只读端点踩过的坑）。
 *
 * <p><b>两处刻意的处理</b>：
 * <ol>
 *   <li><b>显式预热</b>：熔断器是懒创建的——没有任何请求打过 Redis 时，
 *       registry 里根本没有 {@code redis} 这个熔断器，指标自然也是空的。
 *       运维打开 {@code /actuator/prometheus} 看到"一条熔断指标都没有"，
 *       会以为接错了或没有熔断器。这里显式取一次，让依赖级熔断器一定出现在指标里。</li>
 *   <li><b>只导出依赖级</b>：官方集成会给<b>每个</b>熔断器产出约 22 条指标（Prometheus 侧约 44 条序列）。
 *       依赖级只有 2 个，可以忽略；但租户级按 appId 命名、随租户数增长，
 *       上千租户就是几万条序列——而"哪个租户的熔断出过问题"这件事，
 *       已经由 {@link GovernanceMetrics#recordBreakerTransition}（基数 = 出过问题的租户数）承载。
 *       所以这里用 MeterFilter 把租户级（名字形如 {@code redis:appId}）挡在指标之外——
 *       过滤器在 {@link CircuitBreakerCardinalityConfig}（**必须放在别的类里**，见那个类的注释）。</li>
 * </ol>
 *
 * <p><b>为什么用构造器绑定而不是 {@code @Bean MeterBinder}</b>：{@code MeterBinder} 在
 * MeterRegistry 的后处理阶段被解析，理论上可能形成
 * {@code MeterRegistry → MeterBinder → DependencyCircuitBreakers → MeterRegistry} 的创建环
 * （此为装配推理，未在真容器复现）。构造器注入把顺序写死，完全绕开这个问题。
 */
@Configuration
public class CircuitBreakerMetricsConfig {

    public CircuitBreakerMetricsConfig(DependencyCircuitBreakers breakers,
                                       MeterRegistry meterRegistry) {
        // ① 预热：让依赖级熔断器一定存在（见类注释）
        breakers.redis();
        breakers.mysql();

        // ② 绑定官方指标（自动跟进后续新建/移除的熔断器）
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers.registry())
                .bindTo(meterRegistry);
    }

}
