package com.newcoupon.integration;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.CouponIssueTaskService;
import com.newcoupon.application.service.CouponIssueTaskWorker;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片组线程（issue-task-shard-group）的 trace 传播契约（2026-11-12 审查 M1）。
 *
 * <p><b>为什么单测这个池</b>：worker 的 taskPool 只负责把 chunk 递给
 * {@link CouponIssueTaskProcessor}，批量事务失败后"按分片拆组"的中路径跑在它内部
 * <b>懒创建</b>的 groupPool（issue-task-shard-group）上——组内再失败逐条兜底的
 * 落库里程碑 span（coupon-issue-persist）就诞生在这批线程上。它漏包时，恰在
 * 最需要排查的场景（批量失败/降级兜底）里 trace 断线：里程碑裂成新 traceId，
 * Jaeger 上看不到它属于哪一轮 worker。
 *
 * <p><b>场景构造</b>：raw-bulk 关闭时 processAll 走逐条且不抛（里程碑落在已被包装的
 * taskPool 线程上，够不到 groupPool）；groupPool 只在 <b>processAll 抛异常</b>后的
 * 分片组兜底才进场（生产对应：raw-bulk 批量写失败，或模板状态在调度间隙变化的
 * assertReceivable 抛出）。本用例用 @SpyBean 让 processAll 抛异常，逼处理器走
 * "整批失败→分片组→组内逐条"的真实降级链——process(group) 本体仍是真货。
 *
 * <p><b>验证方式</b>：真实建模板+任务，在一个父 span 的作用域里跑
 * {@code worker.runOnce()}（runOnce 内部建 issue-worker-round 子 span 并 makeCurrent
 * ——与生产形态一致），用测试内注册的 {@link SpanProcessor} 同步收集 ended span，
 * 断言<b>分片组线程上</b>产生的 coupon-issue-persist 与父 span **同 traceId**。
 * 与 {@link TracingAsyncPropagationIntegrationTest}（机制级）互为补充：这里钉的是
 * 真实业务链路的最后一跳（调度线程→taskPool→groupPool→事务线程）。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.tracing.enabled=true",
        "coupon.stock.shard-count=8",
        "coupon.issue.async-enabled=true",
        "coupon.issue.async-template-ids=*",
        "coupon.issue.worker.enabled=true",
        // raw-bulk 关闭：单任务事务路径必经 issueC1（里程碑 span 的产生点）
        "coupon.issue.worker.raw-bulk-enabled=false",
        // 调度推迟一小时：本用例显式 runOnce()，不让后台轮次抢走任务
        "coupon.issue.worker.initial-delay-ms=3600000",
        "coupon.issue.worker.fixed-delay-ms=3600000",
        "coupon.issue.worker.batch-size=10",
        "coupon.issue.worker.batch-tx-size=10"
})
@Import(TracingGroupPoolPropagationIntegrationTest.CollectorConfiguration.class)
class TracingGroupPoolPropagationIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /**
     * 收集 ended span 的处理器：Boot 把自定义 SpanProcessor bean 加进 SdkTracerProvider，
     * onEnd 在 span.end() 时**同步**回调——断言不需要等 BatchSpanProcessor 的导出周期。
     */
    @TestConfiguration
    static class CollectorConfiguration {

        /** 收集容器放静态：与上下文生命周期解耦，测试类直接读（单 JVM 单上下文独占使用） */
        static final List<RecordedSpan> RECORDED = new CopyOnWriteArrayList<>();

        record RecordedSpan(String name, String traceId, String threadName) {
        }

        @Bean
        SpanProcessor spanCollector() {
            return new SpanProcessor() {
                @Override
                public void onStart(Context parentContext, ReadWriteSpan span) {
                    // 只关心 ended 的完整 span，这里不需要
                }

                @Override
                public boolean isStartRequired() {
                    return false;
                }

                @Override
                public void onEnd(ReadableSpan span) {
                    RECORDED.add(new RecordedSpan(span.getName(),
                            span.getSpanContext().getTraceId(),
                            Thread.currentThread().getName()));
                }

                @Override
                public boolean isEndRequired() {
                    return true;
                }

                @Override
                public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
                    // 测试上下文关闭时清空：防上下文缓存复用时串轮
                    RECORDED.clear();
                    return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
                }

                @Override
                public io.opentelemetry.sdk.common.CompletableResultCode forceFlush() {
                    return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
                }
            };
        }
    }

    private static final String APP_ID = "grp-trace-" + Long.toHexString(System.nanoTime());

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private CouponIssueTaskService taskService;
    @Autowired
    private CouponIssueTaskMapper taskMapper;
    @Autowired
    private CouponIssueTaskWorker worker;
    @Autowired
    private Tracer tracer;
    @Autowired
    private JdbcTemplate jdbc;

    /** 只为把 processAll 逼抛一次（见类注释"场景构造"）；process(group) 走真货。 */
    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.newcoupon.application.service.CouponIssueTaskBatchTxService batchTxService;

    private final List<Long> templates = new ArrayList<>();

    @BeforeEach
    void bindAppContext() {
        RequestContext.set(new RequestContext(APP_ID, "grp-trace-test", Long.MAX_VALUE, false));
        CollectorConfiguration.RECORDED.clear();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM coupon_issue_task WHERE app_id = ?", APP_ID);
        for (long templateId : templates) {
            jdbc.update("DELETE FROM coupon_user_daily_counter WHERE template_id = ?", templateId);
            jdbc.update("DELETE FROM coupon_daily_counter WHERE template_id = ?", templateId);
        }
        RequestContext.clear();
    }

    @Test
    @DisplayName("批量失败降级路径：groupPool 线程上的 coupon-issue-persist 与 worker 轮同 traceId")
    void groupPoolThread_milestoneJoinsWorkerRoundTrace() {
        // 前置：一张可领模板 + 一个可领取任务（照 AsyncIssueWorkerRawBulkDisabledIntegrationTest 的造数）
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "组池传播验证券", 3003L, 1, 1000L, null, 10000L, 10L, 1,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                0L, 0));
        templateAppService.publish(created.id());
        templates.add(created.id());
        long userId = 910_000 + (System.currentTimeMillis() % 10_000);
        CouponTemplate template = templateRepository.findById(created.id()).orElseThrow();
        CouponIssueTaskPO task = taskService.accept(
                new ReceiveRequest("grp-trace-" + UUID.randomUUID(), userId, created.id()),
                template, LocalDate.now(), 1);
        jdbc.update("UPDATE coupon_issue_task SET next_scan_time = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0)), task.getId());

        // 逼 processAll 抛异常（见类注释"场景构造"）：处理器随后退回"按分片拆组"，
        // 组内逐条兜底在 groupPool 线程上真正发券——这是要验证传播的最后一跳
        org.mockito.Mockito.doThrow(new RuntimeException("test-forced bulk failure"))
                .when(batchTxService).processAll(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());

        // 父 span 作用域里跑一轮（runOnce 内部建 issue-worker-round 并 makeCurrent——生产形态）
        Span parent = tracer.nextSpan().name("test-round-parent").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            worker.runOnce();
        } finally {
            parent.end();
        }

        // 任务确实成功（否则"没有里程碑 span"可能是没走到落库，断言就失真了）
        CouponIssueTaskPO done = taskMapper.selectById(task.getId());
        assertThat(done.getStatus())
                .as("任务应经分片组逐条兜底成功落库")
                .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);

        // 核心：组线程上的落库里程碑与父 span 同 trace
        List<CollectorConfiguration.RecordedSpan> milestones = CollectorConfiguration.RECORDED.stream()
                .filter(r -> r.name().startsWith("coupon-issue-persist"))
                .toList();
        assertThat(milestones).as("应收集到本模板的落库里程碑 span").isNotEmpty();
        for (CollectorConfiguration.RecordedSpan m : milestones) {
            // 本轮 runOnce 领到的所有任务（含库里残留的他人任务）的里程碑都应挂在本 trace 上
            assertThat(m.traceId())
                    .as("issue-task-shard-group 线程上的里程碑 span 必须挂在 worker 轮 trace 上"
                            + "（漏包 ContextPropagatingExecutors 会裂成新 traceId）。actual=%s", m)
                    .isEqualTo(parent.context().traceId());
            assertThat(m.threadName())
                    .as("里程碑应产生在分片组线程上（否则测的不是 groupPool 路径）")
                    .startsWith("issue-task-shard-group");
        }
        // 佐证：worker 轮 span 也在同一条 trace 里
        assertThat(CollectorConfiguration.RECORDED.stream()
                .anyMatch(r -> r.name().startsWith("issue-worker-round")
                        && r.traceId().equals(parent.context().traceId())))
                .as("issue-worker-round 应为父 span 同 trace 的子 span").isTrue();
    }
}
