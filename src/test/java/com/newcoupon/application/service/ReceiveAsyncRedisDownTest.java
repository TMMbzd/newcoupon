package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.ReceiveAsyncResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

@ExtendWith(MockitoExtension.class)
@DisplayName("异步受理·Redis 故障策略")
class ReceiveAsyncRedisDownTest {

    private static final long TEMPLATE_ID = 5L;
    private static final long USER_ID = 42L;

    @Mock
    private CachedTemplateReader cachedTemplateReader;
    @Mock
    private RedisIssueGate issueGate;
    @Mock
    private CouponStockShardMapper stockShardMapper;
    @Mock
    private CouponIssueTaskService taskService;
    @Mock
    private GateCounterRepairService counterRepairService;
    @Mock
    private GateRebuildService gateRebuildService;
    @Mock
    private GateRepairQueueService gateRepairQueueService;
    @Mock
    private com.newcoupon.infrastructure.redis.SoldOutHint soldOutHint;
    @Mock
    private RiskDecisionService riskDecisionService;   // mock boolean 默认 false=风控开关关

    /** 真实内存注册表：直接断言 reject / db-fallback / redis_error 三个结果标签。 */
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private CouponTemplate template;

    @BeforeEach
    void setUp() {
        template = CouponTemplate.reconstitute(TEMPLATE_ID, CouponTemplate.Creation.builder()
                .templateCode("REDIS-DOWN")
                .activityId(1L)
                .name("Redis 故障测试券")
                .couponType(CouponType.NO_THRESHOLD)
                .faceValue(100L)
                .threshold(0L)
                .totalCount(10L)
                .perUserLimit(1)
                .dailyLimit(0L)
                .perUserDailyLimit(0)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.now().minusDays(1))
                .validEnd(LocalDateTime.now().plusDays(1))
                .build(), 0L, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
        when(cachedTemplateReader.get(TEMPLATE_ID)).thenReturn(Optional.of(template));
    }

    @Test
    @DisplayName("默认 reject：Redis 不可用返回可重试 5001，不落 WAL")
    void defaultPolicyRejectsWithDegraded() {
        when(issueGate.tryAcquire(any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        ReceiveAsyncAppService service = service("reject");

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("redis-down-1", USER_ID, TEMPLATE_ID)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEGRADED);
        verify(taskService, never()).acceptWithResult(any(), any(), any(), eq(0L), any());
        assertThat(metricCount("redis_down_reject")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("db-fallback：Redis 不可用时只写 WAL 返回 202，worker 仍由 DB 终审")
    void dbFallbackWritesWal() {
        when(issueGate.tryAcquire(any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        CouponIssueTaskPO task = new CouponIssueTaskPO();
        task.setId(99L);
        task.setTemplateId(TEMPLATE_ID);
        task.setStatus(CouponIssueTaskPO.STATUS_PENDING);
        when(taskService.acceptWithResult(any(), eq(template), any(), eq(0L),
                eq("async-redis-fallback")))
                .thenReturn(new CouponIssueTaskService.AcceptResult(task, true));
        ReceiveAsyncAppService service = service("db-fallback");

        ReceiveAsyncResult result = service.receive(
                new ReceiveRequest("redis-down-2", USER_ID, TEMPLATE_ID));

        assertThat(result.taskId()).isEqualTo(99L);
        assertThat(result.status()).isEqualTo("ACCEPTED");
        verify(taskService).acceptWithResult(any(), eq(template), any(), eq(0L),
                eq("async-redis-fallback"));
        assertThat(metricCount("redis_down_db-fallback")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("包装成 RedisSystemException 的连接 reset：reject 也走 redis_down_reject，而不是 redis_error")
    void wrappedConnectionReset_underReject_usesRedisDownPolicy() {
        when(issueGate.tryAcquire(any())).thenThrow(wrappedConnectionReset());
        ReceiveAsyncAppService service = service("reject");

        assertThatThrownBy(() -> service.receive(
                new ReceiveRequest("redis-reset-1", USER_ID, TEMPLATE_ID)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEGRADED);
        verify(taskService, never()).acceptWithResult(any(), any(), any(), eq(0L), any());
        assertThat(metricCount("redis_down_reject")).isEqualTo(1.0);
        assertThat(metricSeen("redis_error")).isFalse();
    }

    @Test
    @DisplayName("包装成 RedisSystemException 的连接 reset：db-fallback 仍写 WAL 返回 202")
    void wrappedConnectionReset_underDbFallback_writesWal() {
        when(issueGate.tryAcquire(any())).thenThrow(wrappedConnectionReset());
        CouponIssueTaskPO task = new CouponIssueTaskPO();
        task.setId(100L);
        task.setTemplateId(TEMPLATE_ID);
        task.setStatus(CouponIssueTaskPO.STATUS_PENDING);
        when(taskService.acceptWithResult(any(), eq(template), any(), eq(0L),
                eq("async-redis-fallback")))
                .thenReturn(new CouponIssueTaskService.AcceptResult(task, true));
        ReceiveAsyncAppService service = service("db-fallback");

        ReceiveAsyncResult result = service.receive(
                new ReceiveRequest("redis-reset-2", USER_ID, TEMPLATE_ID));

        assertThat(result.taskId()).isEqualTo(100L);
        assertThat(result.status()).isEqualTo("ACCEPTED");
        verify(taskService).acceptWithResult(any(), eq(template), any(), eq(0L),
                eq("async-redis-fallback"));
        assertThat(metricCount("redis_down_db-fallback")).isEqualTo(1.0);
        assertThat(metricSeen("redis_error")).isFalse();
    }

    @Test
    @DisplayName("非连接类 RedisSystemException（Lua/类型错误）仍返回 5001 + redis_error")
    void nonConnectionSystemError_stillReturnsRedisError() {
        when(issueGate.tryAcquire(any())).thenThrow(nonConnectionSystemError());
        ReceiveAsyncAppService service = service("db-fallback");

        assertThatThrownBy(() -> service.receive(
                new ReceiveRequest("redis-lua-error", USER_ID, TEMPLATE_ID)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEGRADED);
        verify(taskService, never()).acceptWithResult(any(), any(), any(), eq(0L), any());
        assertThat(metricCount("redis_error")).isEqualTo(1.0);
        assertThat(metricSeen("redis_down_db-fallback")).isFalse();
    }

    /** 模拟真实链路：RedisSystemException → Lettuce/包装层 → IOException(Connection reset)。 */
    private static RedisSystemException wrappedConnectionReset() {
        return new RedisSystemException("Redis command failed",
                new IllegalStateException("lettuce command failed",
                        new IOException("Connection reset by peer")));
    }

    /** 非连接类：Lua 脚本/类型错误，不能被误判成依赖不可用。 */
    private static RedisSystemException nonConnectionSystemError() {
        return new RedisSystemException("Redis command failed",
                new IllegalStateException("WRONGTYPE Operation against a key holding the wrong kind of value"));
    }

    private double metricCount(String result) {
        Counter counter = meterRegistry.find("issue_gate_result_total")
                .tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private boolean metricSeen(String result) {
        return meterRegistry.find("issue_gate_result_total").tag("result", result).counter() != null;
    }

    private ReceiveAsyncAppService service(String policy) {
        return new ReceiveAsyncAppService(cachedTemplateReader, issueGate,
                taskService, counterRepairService, gateRebuildService, gateRepairQueueService,
                soldOutHint, meterRegistry, riskDecisionService, null,
                true, true, "*", 604800, 172800, policy);
    }
}
