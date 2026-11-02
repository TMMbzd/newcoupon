package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponSettlePartyDailyBillMapper;
import com.newcoupon.infrastructure.persistence.po.CouponDailyBillPO;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 日账单调度入口的分支：Redis 锁让路/降级、失败指标、XXL 日期参数。
 * 纯单元测试（无 Spring 上下文/容器）——账单聚合本身由 DailyBillIntegrationTest 覆盖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("日账单调度·Redis 锁与失败指标")
class DailyBillSchedulerTest {

    private static final String LOCK_KEY = "c:lock:daily-bill";
    private static final LocalDate DATE = LocalDate.of(2001, 1, 2);

    @Mock
    private CouponDailyBillMapper billMapper;
    @Mock
    private CouponSettlePartyDailyBillMapper settlePartyBillMapper;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private RedisScanLock scanLock;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private DailyBillService service;

    @BeforeEach
    void setUp() {
        // 让路/参数类用例不执行事务；lenient 避免严格 stub 误报
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        service = new DailyBillService(billMapper, settlePartyBillMapper, transactionManager,
                true, scanLock, meterRegistry);
    }

    @Test
    @DisplayName("锁被其他实例持有：本轮让路、零账单查询、skipped 指标 +1")
    void skipsWhenLockBusy() {
        when(scanLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(service.runOnce(DATE)).isZero();

        verifyNoInteractions(billMapper);
        verifyNoInteractions(settlePartyBillMapper);
        verify(scanLock, never()).unlock(anyString(), anyString());
        assertThat(counter("skipped")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("抢到锁：生成账单、释放锁、success 指标与耗时计时器")
    void runsWhenLockFree() {
        when(scanLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(service.runOnce(DATE)).isEqualTo(1);

        verify(billMapper).aggregateByDate(any(), any());
        verify(scanLock).unlock(eq(LOCK_KEY), anyString());
        assertThat(counter("success")).isEqualTo(1.0);
        assertThat(meterRegistry.get("daily_bill_job_duration_seconds").timer().count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("Redis 锁不可用：降级直接生成（T+1 账单错过比重复贵），不尝试释放")
    void degradesWhenLockThrows() {
        when(scanLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(service.runOnce(DATE)).isEqualTo(1);

        verify(billMapper).aggregateByDate(any(), any());
        verify(scanLock, never()).unlock(anyString(), anyString());
        assertThat(counter("degraded")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("生成失败：返回 -1、failed 指标 +1、锁仍被释放")
    void returnsMinusOneAndUnlocksOnFailure() {
        when(scanLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(billMapper.aggregateByDate(any(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThat(service.runOnce(DATE)).isEqualTo(-1);

        verify(scanLock).unlock(eq(LOCK_KEY), anyString());
        assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("对账不一致：主体账单缺失时累计 mismatch 指标")
    void reconcileMismatchIncrementsCounter() {
        when(scanLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        CouponDailyBillMapper.DailyAgg agg = new CouponDailyBillMapper.DailyAgg();
        agg.setTemplateId(999L);
        agg.setConfirmCount(1L);
        agg.setRefundCount(0L);
        agg.setDiscountTotal(2000L);
        agg.setPlatformBurden(2000L);
        agg.setMerchantBurden(0L);
        when(billMapper.aggregateByDate(any(), any())).thenReturn(List.of(agg));

        CouponDailyBillPO bill = new CouponDailyBillPO();
        bill.setTemplateId(999L);
        bill.setConfirmCount(1);
        bill.setRefundCount(0);
        bill.setDiscountTotal(2000L);
        bill.setPlatformBurden(2000L);
        bill.setMerchantBurden(0L);
        when(billMapper.selectByDate(DATE)).thenReturn(List.of(bill));

        assertThat(service.runOnce(DATE)).isEqualTo(1);

        assertThat(meterRegistry.get("daily_bill_reconcile_mismatch_total").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("任务参数：空白=昨日、合法日期原样、非法日期直接报错（不静默补昨日）")
    void parseBillDate() {
        LocalDate fallback = LocalDate.of(2026, 10, 24);

        assertThat(DailyBillService.parseBillDate(null, fallback)).isEqualTo(fallback);
        assertThat(DailyBillService.parseBillDate("  ", fallback)).isEqualTo(fallback);
        assertThat(DailyBillService.parseBillDate("2026-10-01", fallback))
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThatThrownBy(() -> DailyBillService.parseBillDate("2026-13-01", fallback))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    private double counter(String result) {
        return meterRegistry.get("daily_bill_job_total").tag("result", result).counter().count();
    }
}
