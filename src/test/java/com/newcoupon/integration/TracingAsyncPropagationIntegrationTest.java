package com.newcoupon.integration;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步线程的 trace 传播契约（V6 5B 生产化模拟刀 C2）：开关开时，提交线程的 span 上下文
 * 必须随任务带到自建线程池的工作线程——这是 AuditService（audit-writer）与
 * CouponIssueTaskWorker（issue-task-worker）两条链路在 Jaeger 上不裂成两条 trace 的前提。
 *
 * <p>验证方式与生产代码<b>同一机制</b>：{@link ContextPropagatingExecutors} 的包装 +
 * 工作线程内 {@code tracer.nextSpan()}（AuditService.record 的逐字同款模式）——
 * 断言子 span 的 traceId 与提交线程的父 span 相同。机制若不成立（ Observation/OTel
 * 上下文没被快照恢复），本测试红，异步传播的取证（docs/evidence/2026-11-12-Tracing取证.md）
 * 也会裂——测试与取证互为对照。
 */
@SpringBootTest(properties = {
        // 开关开（本类测的是"开"侧的行为；"关"侧见 TracingSwitchIntegrationTest）
        "coupon.tracing.enabled=true"
        // endpoint 保持默认（127.0.0.1:4318，测试环境无 Jaeger）：OTLP 导出失败只产生
        // 后台日志重试，不影响本测试的 span 对象级断言——真实导出链路由 Jaeger 取证覆盖
})
class TracingAsyncPropagationIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private Tracer tracer;

    @Test
    @DisplayName("开关开：Tracer 非空实现；包装执行器里 nextSpan 挂进父 trace（traceId 相同）")
    void wrappedExecutor_childSpanJoinsParentTrace() throws Exception {
        assertThat(tracer)
                .as("开关开应装配真 Tracer（OTel bridge），不是 Noop")
                .isNotSameAs(Tracer.NOOP);

        // 与 AuditService.auditExecutor / CouponIssueTaskWorker.taskPool 同款包装
        ExecutorService wrapped =
                com.newcoupon.infrastructure.tracing.ContextPropagatingExecutors.wrap(
                        Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "test-propagation");
                            t.setDaemon(true);
                            return t;
                        }));
        try {
            Span parent = tracer.nextSpan().name("test-parent").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
                AtomicReference<String> childTraceId = new AtomicReference<>();
                AtomicReference<String> childThread = new AtomicReference<>();
                wrapped.submit(() -> {
                    // 工作线程上创建 span——AuditService.record 里 audit-write span 的同款写法
                    Span child = tracer.nextSpan().name("test-child").start();
                    childTraceId.set(child.context().traceId());
                    childThread.set(Thread.currentThread().getName());
                    child.end();
                }).get(10, TimeUnit.SECONDS);

                assertThat(childTraceId.get())
                        .as("异步线程的子 span 必须挂在父 trace 上（traceId 相同）——"
                                + "裂成新 traceId 说明上下文没有随任务传播")
                        .isEqualTo(parent.context().traceId());
                assertThat(childThread.get()).isEqualTo("test-propagation");
            } finally {
                parent.end();
            }
        } finally {
            wrapped.shutdownNow();
        }
    }
}
