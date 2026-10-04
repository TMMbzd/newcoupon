package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.persistence.mapper.CouponOutboxMapper;
import com.newcoupon.infrastructure.persistence.po.CouponOutboxPO;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.newcoupon.application.service.SwitchService;

/**
 * L40④（2026-09-30）：发件箱的**退避与死信**此前零覆盖（默认套件只测了"同事务双写"与"投递闭环"）。
 * 本类用单测钉住两条失败语义：
 * <ul>
 *   <li>投递失败且未到上限 → 只退避（markRetry），不置死信；</li>
 *   <li>投递失败且已到上限（retry+1 ≥ max-retry）→ 置死信 status=2 + 落 OUTBOX_DEAD 告警，不 markRetry。</li>
 * </ul>
 */
class OutboxDispatcherTest {

    private final CouponOutboxMapper mapper = mock(CouponOutboxMapper.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final RedisScanLock scanLock = mock(RedisScanLock.class);
    private final StockDriftLogGateway driftLog = mock(StockDriftLogGateway.class);
    private final OutboxDispatcher dispatcher =
            new OutboxDispatcher(mapper, publisher, scanLock, driftLog,
                    // 投递开关是**全局**的：定时任务没有接入方身份，永远走全站层——
                    // 所以这里的 TenantConfigService 不会被调用，给个 mock 即可
                    new SwitchService(new org.springframework.core.env.StandardEnvironment(),
                            mock(TenantConfigService.class)), 100, 8);

    private CouponOutboxPO row(int retryCount) {
        CouponOutboxPO po = new CouponOutboxPO();
        po.setId(1L);
        po.setEventNo("RCV:1");
        po.setEventType(1);
        po.setPayload("{}");
        po.setStatus(0);
        po.setRetryCount(retryCount);
        po.setNextScanTime(LocalDateTime.now());
        return po;
    }

    private void givenOneRowThenEmpty(CouponOutboxPO po) {
        when(scanLock.tryLock(anyString(), anyString(), any())).thenReturn(true);
        when(mapper.selectDispatchable(any(), anyInt())).thenReturn(List.of(po), List.of());
    }

    @Test
    @DisplayName("投递失败未到上限：只退避（markRetry），不置死信")
    void failure_belowLimit_retriesWithBackoff() {
        givenOneRowThenEmpty(row(0));
        org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
                .when(publisher).publish(anyString(), anyString(), anyString());

        dispatcher.dispatch();

        verify(mapper).markRetry(eq(1L), any(java.time.LocalDateTime.class), anyString());
        verify(mapper, never()).markDead(anyLong(), anyString());
        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("投递失败已到上限（retry=7, max=8）：置死信 status=2 + 落 OUTBOX_DEAD 告警，行保留")
    void failure_atLimit_deadLetterWithAlarm() {
        givenOneRowThenEmpty(row(7));
        org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
                .when(publisher).publish(anyString(), anyString(), anyString());

        dispatcher.dispatch();

        verify(mapper).markDead(eq(1L), anyString());
        verify(mapper, never()).markRetry(anyLong(), any(java.time.LocalDateTime.class), anyString());
        var captor = org.mockito.ArgumentCaptor.forClass(StockDriftLogGateway.DriftEvent.class);
        verify(driftLog).record(captor.capture());
        assertThat(captor.getValue().action()).as("死信必须落漂移表（人工介入信号）").isEqualTo("OUTBOX_DEAD");
    }

    @Test
    @DisplayName("L41·T+1 守恒审计：行数差额为 0 → 不告警")
    void audit_cleanWhenCountsMatch() {
        when(scanLock.tryLock(anyString(), anyString(), any())).thenReturn(true);
        when(mapper.issuedEventMinusOutbox()).thenReturn(0L);

        dispatcher.outboxAudit();

        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("L41·T+1 守恒审计：差额非 0 → 落 OUTBOX_MISMATCH 告警（只告警不自动修）")
    void audit_alarmsOnMismatch() {
        when(scanLock.tryLock(anyString(), anyString(), any())).thenReturn(true);
        when(mapper.issuedEventMinusOutbox()).thenReturn(-3L);

        dispatcher.outboxAudit();

        var captor = org.mockito.ArgumentCaptor.forClass(StockDriftLogGateway.DriftEvent.class);
        verify(driftLog).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("OUTBOX_MISMATCH");
    }

    @Test
    @DisplayName("投递成功：markDispatched（且不触发任何失败分支）")
    void success_marksDispatched() {
        givenOneRowThenEmpty(row(0));

        dispatcher.dispatch();

        verify(mapper).markDispatched(1L);
        verify(mapper, never()).markRetry(anyLong(), any(java.time.LocalDateTime.class), anyString());
        verify(mapper, never()).markDead(anyLong(), anyString());
    }
}
