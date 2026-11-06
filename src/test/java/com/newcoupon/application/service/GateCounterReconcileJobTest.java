package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 计数对账任务的单元合同（讨论 02 核对结论 P4）：
 * 读序（先 GET 再算目标）、阈值过滤、损坏值直接重写、缺失键跳过、漂移留痕、不动 seq/idem。
 */
class GateCounterReconcileJobTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RedisIssueGate issueGate;
    private GateCounterRepairService counterRepairService;
    private GateRebuildTxService gateRebuildTxService;
    private GateRebuildService gateRebuildService;
    private StockDriftLogGateway driftLog;
    private RedisScanLock scanLock;
    private GateCounterReconcileJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        issueGate = mock(RedisIssueGate.class);
        counterRepairService = mock(GateCounterRepairService.class);
        gateRebuildTxService = mock(GateRebuildTxService.class);
        gateRebuildService = mock(GateRebuildService.class);
        driftLog = mock(StockDriftLogGateway.class);
        scanLock = mock(RedisScanLock.class);
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.counter(any(), any(), any())).thenReturn(mock(Counter.class));
        job = new GateCounterReconcileJob(redis, issueGate, counterRepairService,
                gateRebuildTxService, gateRebuildService, driftLog, scanLock, registry,
                0, 500, 300000, 604800, 172800);
    }

    @Test
    @DisplayName("ul 偏低：按 DB 目标修高并落漂移日志")
    void ulLowIsFixedToTarget() {
        String key = "c:issue:ul:{501}:7";
        when(valueOps.get(key)).thenReturn("2");
        when(counterRepairService.computeUserTotalTarget(7L, 501L)).thenReturn(3L);

        job.reconcileUserTotalKey(key);

        verify(issueGate).repairUserTotal(501L, 7L, 3L, 604800L);
        ArgumentCaptor<StockDriftLogGateway.DriftEvent> cap =
                ArgumentCaptor.forClass(StockDriftLogGateway.DriftEvent.class);
        verify(driftLog).record(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("ul_reconcile_fix");
        assertThat(cap.getValue().detail()).contains("redis=2").contains("target=3");
    }

    @Test
    @DisplayName("ul 无偏差：只检查不写、不落漂移")
    void ulMatchingSkipped() {
        String key = "c:issue:ul:{501}:7";
        when(valueOps.get(key)).thenReturn("3");
        when(counterRepairService.computeUserTotalTarget(7L, 501L)).thenReturn(3L);

        job.reconcileUserTotalKey(key);

        verify(issueGate, never()).repairUserTotal(anyLong(), anyLong(), anyLong(), anyLong());
        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("ul 损坏（非数字）：直接按目标重写")
    void ulCorruptIsRewritten() {
        String key = "c:issue:ul:{501}:7";
        when(valueOps.get(key)).thenReturn("abc");
        when(counterRepairService.computeUserTotalTarget(7L, 501L)).thenReturn(1L);

        job.reconcileUserTotalKey(key);

        verify(issueGate).repairUserTotal(501L, 7L, 1L, 604800L);
    }

    @Test
    @DisplayName("键缺失（SCAN 与 GET 之间过期）：跳过，交给访问路径自愈")
    void missingKeySkipped() {
        when(valueOps.get("c:issue:ul:{501}:7")).thenReturn(null);

        job.reconcileUserTotalKey("c:issue:ul:{501}:7");

        verify(counterRepairService, never()).computeUserTotalTarget(anyLong(), anyLong());
    }

    @Test
    @DisplayName("gate 偏高：超阈值才 rebuild(force)；无偏差时只算不写")
    void gateCheckedBeforeRewrite() {
        String key = "c:issue:gate:{501}";
        when(valueOps.get(key)).thenReturn("10");
        when(gateRebuildTxService.computeRemaining(501L)).thenReturn(5L);

        job.reconcileGateKey(key);

        verify(gateRebuildService).rebuild(501L, true);
        verify(driftLog).record(any());

        // 无偏差：不 rebuild
        org.mockito.Mockito.clearInvocations(gateRebuildService, driftLog);
        when(gateRebuildTxService.computeRemaining(501L)).thenReturn(10L);
        job.reconcileGateKey(key);
        verify(gateRebuildService, never()).rebuild(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("uld 往日键跳过（不再有新写入，等 TTL）；今日键按目标修")
    void uldPastDaySkippedTodayFixed() {
        LocalDate today = LocalDate.now();
        String pastKey = "c:issue:uld:{501}:7:" + today.minusDays(1);
        when(valueOps.get(pastKey)).thenReturn("1");

        job.reconcileUserDailyKey(pastKey);
        verify(counterRepairService, never())
                .computeUserDailyTarget(anyLong(), anyLong(), any());

        String todayKey = "c:issue:uld:{501}:7:" + today;
        when(valueOps.get(todayKey)).thenReturn("0");
        when(counterRepairService.computeUserDailyTarget(7L, 501L, today)).thenReturn(2L);

        job.reconcileUserDailyKey(todayKey);
        verify(issueGate).repairUserDaily(501L, 7L, today, 2L, 172800L);
    }

    @Test
    @DisplayName("dly 今日键按目标修；格式不匹配的键忽略")
    void dlyTodayFixedAndUnknownIgnored() {
        LocalDate today = LocalDate.now();
        String key = "c:issue:dly:{501}:" + today;
        when(valueOps.get(key)).thenReturn("99");
        when(counterRepairService.computeTemplateDailyTarget(501L, today)).thenReturn(5L);

        job.reconcileTemplateDailyKey(key);
        verify(issueGate).repairTemplateDaily(501L, today, 5L, 172800L);

        job.reconcileTemplateDailyKey("c:issue:dly:{501}:not-a-date");
        job.reconcileTemplateDailyKey("c:issue:seq:{501}:7");   // seq/idem 不属于对账范围
        // 仍只有上面那一次合法调用——未知格式与 seq 键都被忽略
        verify(issueGate, org.mockito.Mockito.times(1))
                .repairTemplateDaily(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("阈值>0：差值在阈值内不修")
    void thresholdToleratesSmallDiff() {
        GateCounterReconcileJob tolerant = new GateCounterReconcileJob(redis, issueGate,
                counterRepairService, gateRebuildTxService, gateRebuildService,
                driftLog, scanLock, mock(MeterRegistry.class),
                2, 500, 300000, 604800, 172800);
        when(valueOps.get("c:issue:ul:{501}:7")).thenReturn("3");
        when(counterRepairService.computeUserTotalTarget(7L, 501L)).thenReturn(5L);

        tolerant.reconcileUserTotalKey("c:issue:ul:{501}:7");

        verify(issueGate, never()).repairUserTotal(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("读序：先 GET Redis 当前值，再查 DB 目标（并发受理对两边同向 +1，diff 不变）")
    void readsRedisBeforeComputingTarget() {
        String key = "c:issue:ul:{501}:7";
        when(valueOps.get(key)).thenReturn("1");
        when(counterRepairService.computeUserTotalTarget(7L, 501L)).thenReturn(4L);

        job.reconcileUserTotalKey(key);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(valueOps, counterRepairService);
        order.verify(valueOps).get(key);
        order.verify(counterRepairService).computeUserTotalTarget(7L, 501L);
    }

    @Test
    @DisplayName("gate 损坏值：按目标重写，漂移 delta 用写入值计算（不产生溢出天文数字）")
    void gateCorruptRewritesWithSaneDelta() {
        String key = "c:issue:gate:{501}";
        when(valueOps.get(key)).thenReturn("not-a-number");
        when(gateRebuildTxService.computeRemaining(501L)).thenReturn(6L);
        when(gateRebuildService.rebuild(501L, true)).thenReturn(6L);

        job.reconcileGateKey(key);

        verify(gateRebuildService).rebuild(501L, true);
        ArgumentCaptor<StockDriftLogGateway.DriftEvent> cap =
                ArgumentCaptor.forClass(StockDriftLogGateway.DriftEvent.class);
        verify(driftLog).record(cap.capture());
        assertThat(cap.getValue().delta()).isEqualTo(6L);   // 损坏→目标，不是 MIN_VALUE 溢出差
    }

    @Test
    @DisplayName("gate ±1 容差：受理提交窗口的瞬态差不修，±2 起才修")
    void gateToleranceAbsorbsAdmissionWindow() {
        String key = "c:issue:gate:{501}";
        when(gateRebuildTxService.computeRemaining(501L)).thenReturn(10L);

        when(valueOps.get(key)).thenReturn("9");   // diff=+1：受理毫秒窗口噪声
        job.reconcileGateKey(key);
        verify(gateRebuildService, never()).rebuild(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());

        when(valueOps.get(key)).thenReturn("7");   // diff=+3：真漂移
        job.reconcileGateKey(key);
        verify(gateRebuildService).rebuild(501L, true);
    }

    @Test
    @DisplayName("dly ±1 容差：受理提交窗口的瞬态差不修，±2 起才修（L78）")
    void dlyToleranceAbsorbsAdmissionWindow() {
        LocalDate today = LocalDate.now();
        String key = "c:issue:dly:{501}:" + today;
        when(counterRepairService.computeTemplateDailyTarget(501L, today)).thenReturn(10L);

        when(valueOps.get(key)).thenReturn("9");   // diff=+1：受理 Lua INCR 与任务落库之间的窗口噪声
        job.reconcileTemplateDailyKey(key);
        verify(issueGate, never())
                .repairTemplateDaily(anyLong(), any(), anyLong(), anyLong());

        when(valueOps.get(key)).thenReturn("7");   // diff=+3：真漂移
        job.reconcileTemplateDailyKey(key);
        verify(issueGate).repairTemplateDaily(501L, today, 10L, 172800L);
    }

    @Test
    @DisplayName("dly 默认容差恰好 1：diff=2 属于真漂移，必须修（锁定不能放大成 2）")
    void dlyDefaultToleranceIsExactlyOne() {
        LocalDate today = LocalDate.now();
        String key = "c:issue:dly:{501}:" + today;
        when(counterRepairService.computeTemplateDailyTarget(501L, today)).thenReturn(10L);

        when(valueOps.get(key)).thenReturn("8");   // diff=+2
        job.reconcileTemplateDailyKey(key);
        verify(issueGate).repairTemplateDaily(501L, today, 10L, 172800L);
    }

    @Test
    @DisplayName("dly 配置阈值>1 时沿用配置值（threshold=2：diff=2 不修，diff=3 修）")
    void dlyConfiguredThresholdAboveOneIsRespected() {
        LocalDate today = LocalDate.now();
        String key = "c:issue:dly:{501}:" + today;
        GateCounterReconcileJob tolerant = new GateCounterReconcileJob(redis, issueGate,
                counterRepairService, gateRebuildTxService, gateRebuildService,
                driftLog, scanLock, mock(MeterRegistry.class),
                2, 500, 300000, 604800, 172800);
        when(counterRepairService.computeTemplateDailyTarget(501L, today)).thenReturn(10L);

        when(valueOps.get(key)).thenReturn("8");   // diff=+2：在配置阈值内
        tolerant.reconcileTemplateDailyKey(key);
        verify(issueGate, never())
                .repairTemplateDaily(anyLong(), any(), anyLong(), anyLong());

        when(valueOps.get(key)).thenReturn("7");   // diff=+3：超配置阈值
        tolerant.reconcileTemplateDailyKey(key);
        verify(issueGate).repairTemplateDaily(501L, today, 10L, 172800L);
    }

    @Test
    @DisplayName("dly 往日键与缺失键都跳过；今日键照常对账")
    void dlyPastDayAndMissingSkipped() {
        LocalDate today = LocalDate.now();
        String pastKey = "c:issue:dly:{501}:" + today.minusDays(1);
        when(valueOps.get(pastKey)).thenReturn("5");

        job.reconcileTemplateDailyKey(pastKey);
        verify(counterRepairService, never()).computeTemplateDailyTarget(anyLong(), any());

        when(valueOps.get("c:issue:dly:{501}:" + today)).thenReturn(null);
        job.reconcileTemplateDailyKey("c:issue:dly:{501}:" + today);
        verify(counterRepairService, never()).computeTemplateDailyTarget(anyLong(), any());
    }
}
