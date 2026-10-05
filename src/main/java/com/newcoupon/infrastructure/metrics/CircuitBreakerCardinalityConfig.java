package com.newcoupon.infrastructure.metrics;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 熔断指标的**基数防线**（S2 第 11 步）。
 *
 * <p>官方集成会给<b>每个</b>熔断器产出约 22 条指标（Prometheus 侧约 44 条序列）。
 * 依赖级只有 {@code redis}/{@code mysql} 两个，可以忽略；但租户级按 appId 命名、
 * 随租户数增长，上千租户就是几万条序列 —— 而"哪个租户的熔断出过问题"这件事，
 * 已经由 {@link GovernanceMetrics#recordBreakerTransition}（基数 = 出过问题的租户数）承载。
 * 所以这里把租户级（名字形如 {@code redis:appId}，带冒号）挡在指标之外。
 *
 * <p><b>为什么这个 {@code MeterFilter} 必须单独一个类、且不依赖任何东西</b>——
 * 这是实测踩出来的（2026-10-05）：
 * Spring 创建 {@code MeterRegistry} 时会先收集**所有** {@code MeterFilter} bean；
 * 如果过滤器与被绑定到 registry 的配置写在同一个类里（那个类构造器要注入 {@code MeterRegistry}），
 * 就会形成
 * <pre>
 * MeterRegistry → MeterFilter（同一配置类）→ DependencyCircuitBreakers
 *   → GovernanceMetrics → MeterRegistry      ✗ 循环
 * </pre>
 * 启动直接失败：{@code Requested bean is currently in creation: Is there an unresolvable circular reference?}
 * 拆成"无依赖的过滤器"与"要 registry 的绑定"两个类，环就断了。
 */
@Configuration
public class CircuitBreakerCardinalityConfig {

    @Bean
    MeterFilter dependencyOnlyCircuitBreakerFilter() {
        return MeterFilter.deny(id -> {
            if (!id.getName().startsWith("resilience4j.circuitbreaker.")) {
                return false;   // 非熔断指标不管
            }
            String breaker = id.getTag("name");
            return breaker != null && breaker.contains(":");
        });
    }
}
