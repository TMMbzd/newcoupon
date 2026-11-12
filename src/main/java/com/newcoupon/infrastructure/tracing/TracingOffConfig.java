package com.newcoupon.infrastructure.tracing;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tracing 总开关的"关"侧装配（V6 5B 生产化模拟刀 C2）。
 *
 * <p><b>为什么需要这个类（Boot 3.3 的开关语义缺口，2026-11-12 实测）</b>：
 * {@code management.tracing.enabled=false} 只把 {@code @ConditionalOnEnabledTracing}
 * 标注的 bean（OTLP 导出器等）拦掉——**OtelTracer/SdkTracerProvider/Sampler 仍会装配**
 * （OpenTelemetryAutoConfiguration 的这几个 @Bean 没挂该条件）。后果：业务代码调
 * {@code tracer.nextSpan()} 仍会拿到带真实 traceId 的 span 对象（没有导出器，落不了
 * 任何后端，但对象分配/ID 生成的开销在）。开关承诺的是"关 = 零开销零导出"，所以这里
 * 在<b>关的时候主动提供 {@link Tracer#NOOP}</b>——Boot 的 {@code micrometerOtelTracer}
 * 是 {@code @ConditionalOnMissingBean(io.micrometer.tracing.Tracer.class)}，用户配置的
 * bean 优先，真实 OtelTracer 让位。
 *
 * <p>效果（TracingSwitchIntegrationTest 钉住三条）：注入点拿到的 Tracer 是 NOOP；
 * 上下文无任何 SpanExporter bean；{@code nextSpan()} 返回空 traceId 的无效 span——
 * 业务代码全程零分支（开关语义全部收在装配层）。开关开（{@code coupon.tracing.enabled=true}）
 * 时本配置整体不生效，Boot 装配完整 OTel bridge + OTLP 导出。
 */
@Configuration
@ConditionalOnProperty(prefix = "coupon.tracing", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class TracingOffConfig {

    @Bean
    Tracer couponNoopTracer() {
        return Tracer.NOOP;
    }
}
