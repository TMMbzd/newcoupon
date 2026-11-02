package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("异步受理·修复单飞与一次重试")
class ReceiveAsyncRepairSingleFlightTest {

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
    private MeterRegistry meterRegistry;

    private CouponTemplate template;

    @BeforeEach
    void setUp() {
        template = CouponTemplate.reconstitute(TEMPLATE_ID, CouponTemplate.Creation.builder()
                .templateCode("REPAIR-SINGLE-FLIGHT")
                .activityId(1L)
                .name("修复单飞测试券")
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
    @DisplayName("损坏 gate 只修一次、只重试一次，成功后释放单飞许可")
    void repairsOnceAndRetriesOnce() {
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.GATE_CORRUPT, 0))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.OK, 7));
        when(issueGate.tryAcquireRepairPermit(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        CouponIssueTaskPO task = acceptedTask();
        when(taskService.acceptWithResult(any(), any(), any(), eq(7L)))
                .thenReturn(new CouponIssueTaskService.AcceptResult(task, true));

        ReceiveAsyncResult result = service("reject").receive(request());

        assertThat(result.taskId()).isEqualTo(99L);
        verify(issueGate, times(2)).tryAcquire(any());
        verify(counterRepairService).repairAll(any());
        verify(issueGate).releaseRepairPermit(anyString(), anyString());
    }

    @Test
    @DisplayName("单飞许可被占用时直接返回当前错误，不碰数据库、不登记（推广 B）")
    void permitMissReturnsWithoutRepair() {
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.GATE_CORRUPT, 0));
        when(issueGate.tryAcquireRepairPermit(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> service("reject").receive(request()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEGRADED);

        verify(issueGate, times(1)).tryAcquire(any());
        verify(counterRepairService, never()).repairAll(any());
        verify(gateRepairQueueService, never()).enqueue(any(), anyInt());   // 推广 B：输家不登记
        verify(issueGate, never()).releaseRepairPermit(anyString(), anyString());
    }

    @Test
    @DisplayName("修复后仍然损坏：不再修第二轮，许可留作 2 秒冷却")
    void doesNotRepairAgainWhenRetryStillFails() {
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.GATE_CORRUPT, 0))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.GATE_CORRUPT, 0));
        when(issueGate.tryAcquireRepairPermit(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> service("reject").receive(request()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEGRADED);

        verify(issueGate, times(2)).tryAcquire(any());
        verify(counterRepairService, times(1)).repairAll(any());
        verify(gateRepairQueueService).enqueue(any(), eq(RedisIssueGate.GATE_CORRUPT));
        verify(issueGate, never()).releaseRepairPermit(anyString(), anyString());
    }

    @Test
    @DisplayName("数据库确认真的达限：不执行修复，许可留作冷却")
    void realLimitDoesNotRepair() {
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.USER_TOTAL_LIMIT, 0));
        when(issueGate.tryAcquireRepairPermit(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(counterRepairService.repairLimitIfAllowed(any(), anyInt())).thenReturn(false);

        assertThatThrownBy(() -> service("reject").receive(request()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_LIMIT_EXCEEDED);

        verify(counterRepairService, never()).repairAll(any());
        verify(gateRepairQueueService, never()).enqueue(any(), anyInt());
        verify(issueGate, never()).releaseRepairPermit(anyString(), anyString());
    }

    @Test
    @DisplayName("真售罄：-7 输家不登记修复队列（洪峰降本 B）")
    void stockEmptyLoserSkipsQueue() {
        when(issueGate.tryAcquireRepairPermit(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.STOCK_GATE_EMPTY, 0));

        assertThatThrownBy(() -> service("reject").receive(request()))
                .isInstanceOf(BizException.class);

        verify(gateRepairQueueService, never()).enqueue(any(), anyInt());
    }

    @Test
    @DisplayName("真售罄：修复重试仍 -7 也不登记（赢家重建照常，假售罄发现保留）")
    void stockEmptyRetryFailedSkipsQueue() {
        when(issueGate.tryAcquireRepairPermit(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.STOCK_GATE_EMPTY, 0))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.STOCK_GATE_EMPTY, 0));
        when(gateRebuildService.rebuild(TEMPLATE_ID, true)).thenReturn(0L);
        when(soldOutHint.isSet(TEMPLATE_ID)).thenReturn(false);   // 首轮：提示未写，走完整修复路径

        assertThatThrownBy(() -> service("reject").receive(request()))
                .isInstanceOf(BizException.class);

        verify(gateRebuildService).rebuild(TEMPLATE_ID, true);   // 赢家重建照常
        verify(gateRepairQueueService, never()).enqueue(any(), anyInt());
    }

    @Test
    @DisplayName("售罄提示命中：-7 直接返回，不抢许可不重建（洪峰降本 A）")
    void soldOutHintShortCircuitsRepair() {
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.STOCK_GATE_EMPTY, 0));
        when(soldOutHint.isSet(TEMPLATE_ID)).thenReturn(true);   // 窗口内：提示已写

        assertThatThrownBy(() -> service("reject").receive(request()))
                .isInstanceOf(BizException.class);

        verify(issueGate, never()).tryAcquireRepairPermit(anyString(), anyString(), any());
        verify(gateRebuildService, never()).rebuild(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(gateRepairQueueService, never()).enqueue(any(), anyInt());
    }

    @Test
    @DisplayName("提示短路只对 -7 生效：-9 赢家修复失败才登记，输家不登记（推广 B）")
    void hintOnlyAppliesToStockEmpty() {
        when(issueGate.tryAcquireRepairPermit(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);   // -9 的赢家：修复后重试仍失败才登记
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.GATE_MISSING, 0))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.GATE_MISSING, 0));
        when(gateRebuildService.rebuild(TEMPLATE_ID, false)).thenReturn(5L);
        // -9（gate 缺失）不是 -7：短路根本不会去查提示

        assertThatThrownBy(() -> service("reject").receive(request()))
                .isInstanceOf(BizException.class);

        verify(gateRepairQueueService).enqueue(any(), eq(RedisIssueGate.GATE_MISSING));
    }

    @Test
    @DisplayName("推广 B：任意可修复码的输家都不登记（-9 输家）")
    void anyCodeLoserSkipsQueue() {
        when(issueGate.tryAcquireRepairPermit(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.GATE_MISSING, 0));

        assertThatThrownBy(() -> service("reject").receive(request()))
                .isInstanceOf(BizException.class);

        verify(gateRepairQueueService, never()).enqueue(any(), anyInt());
    }

    @Test
    @DisplayName("模板级错误用模板许可，用户级错误用模板+用户许可")
    void repairPermitScopesByError() {
        when(issueGate.tryAcquire(any()))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.GATE_CORRUPT, 0))
                .thenReturn(new RedisIssueGate.GateResult(RedisIssueGate.USER_TOTAL_LIMIT, 0));
        when(issueGate.tryAcquireRepairPermit(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> service("reject").receive(requestSafely()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service("reject").receive(requestSafely()))
                .isInstanceOf(BizException.class);

        verify(issueGate).tryAcquireRepairPermit(
                eq("c:issue:gate:repair:{5}"), anyString(), any(Duration.class));
        verify(issueGate).tryAcquireRepairPermit(
                eq("c:issue:gate:repair:{5}:42"), anyString(), any(Duration.class));
    }

    private ReceiveRequest requestSafely() {
        return new ReceiveRequest("repair-scope-" + System.nanoTime(), USER_ID, TEMPLATE_ID);
    }

    private ReceiveRequest request() {
        return new ReceiveRequest("repair-single-flight", USER_ID, TEMPLATE_ID);
    }

    private CouponIssueTaskPO acceptedTask() {
        CouponIssueTaskPO task = new CouponIssueTaskPO();
        task.setId(99L);
        task.setTemplateId(TEMPLATE_ID);
        task.setStatus(CouponIssueTaskPO.STATUS_PENDING);
        return task;
    }

    private ReceiveAsyncAppService service(String policy) {
        return new ReceiveAsyncAppService(cachedTemplateReader, issueGate,
                taskService, counterRepairService, gateRebuildService, gateRepairQueueService,
                soldOutHint, meterRegistry, true, true, "*", 604800, 172800, policy);
    }
}
