package com.newcoupon.integration;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracing 总开关契约（V6 5B 生产化模拟刀 C2）：{@code coupon.tracing.enabled=false}（默认）
 * 必须等于"<b>零导出、零业务开销</b>"——与风控开关同一条纪律（docs/runbook/Tracing与E2E.md）。
 *
 * <p>Boot 3.3 的开关语义缺口与补法（2026-11-12 实测，见 {@link TracingOffConfig}）：
 * {@code management.tracing.enabled=false} 只拦导出器，OtelTracer 仍会装配、业务代码
 * 仍会创建带真实 traceId 的 span 对象——所以项目在"关"时由 {@code TracingOffConfig}
 * 主动提供 {@link Tracer#NOOP}（Boot 的真实 OtelTracer 是 @ConditionalOnMissingBean，
 * 让位）。本测试钉住三条可验证后果：
 * <ol>
 *   <li>注入的 Tracer 就是 {@link Tracer#NOOP}（业务代码零分支的根据）；</li>
 *   <li>上下文里<b>不存在任何 SpanExporter bean</b>——没有导出器就没有导出路径
 *       （"零导出"的 bean 级证明；运行级由 Jaeger 取证对照钉住）；</li>
 *   <li>创建 span 拿到的是<b>空 traceId</b>（无效 span——"零开销"的行为面：
 *       热路径上拿到的是空对象，没有 SpanData/采样/ID 生成发生）。</li>
 * </ol>
 * 三条任一不成立（如升级 Boot 后语义漂移），本测试红——开关的承诺由测试看住。
 */
@SpringBootTest
class TracingSwitchIntegrationTest extends ShardedIntegrationTestSupport {

    // 不开 coupon.tracing.enabled——默认 false 正是被测行为（application.yml 的 TRACING_ENABLED 默认）

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private Tracer tracer;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("开关关（默认）：NoopTracer + 无 SpanExporter bean（零导出）+ 空 traceId（零记录）")
    void tracingOff_noopTracerNoExporter() {
        // 零分支的根据：注入点拿到的是 NOOP 本尊（TracingOffConfig 在"关"时提供）
        assertThat(tracer)
                .as("默认关应注入 Tracer.NOOP（TracingOffConfig）")
                .isSameAs(Tracer.NOOP);

        // 零导出：OTLP 导出器在 tracing.enabled=false 时不装配——即使 endpoint 配了值也没用
        int exporterBeans =
                context.getBeanNamesForType(io.opentelemetry.sdk.trace.export.SpanExporter.class).length;
        assertThat(exporterBeans)
                .as("SpanExporter bean 数应为 0，实际=" + exporterBeans)
                .isZero();

        // 零记录：热路径代码无分支地调 tracer.nextSpan()，关的时候拿到的必须是无效 span
        //（traceId 空——没有 SpanData、没有采样，只有一次空对象返回）
        io.micrometer.tracing.Span span = tracer.nextSpan().name("should-not-be-recorded").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(span.context().traceId())
                    .as("关开关时业务代码拿到的应是无效 span（空 traceId）")
                    .isBlank();
        } finally {
            span.end();
        }
    }
}
