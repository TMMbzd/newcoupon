package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.newcoupon.domain.repository.PricingSnapshotRepository;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 价格快照清理调度入口的分支：锁让路/Redis 故障让路、失败指标、行数指标。
 * 清理 SQL 语义由 PricingSnapshotIntegrationTest.P14 覆盖，这里只钉调度行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("价格快照清理调度·Redis 锁与失败指标")
class PricingSnapshotCleanupSchedulerTest {

    private static final String LOCK_KEY = "c:lock:pricing-snapshot-cleanup";

    @Mock
    private PricingSnapshotRepository repository;
    @Mock
    private RedisScanLock scanLock;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private PricingSnapshotCleanupService service;

    @BeforeEach
    void setUp() {
        service = new PricingSnapshotCleanupService(repository, scanLock, meterRegistry);
    }

    @Test
    @DisplayName("锁被其他实例持有：本轮让路、零清理、skipped 指标 +1")
    void skipsWhenLockBusy() {
        when(scanLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(service.runOnce()).isZero();

        verifyNoInteractions(repository);
        verify(scanLock, never()).unlock(anyString(), anyString());
        assertThat(counter("skipped")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 锁不可用：本轮让路（下个小时自愈），lock_error 指标 +1")
    void skipsWhenLockThrows() {
        when(scanLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(service.runOnce()).isZero();

        verifyNoInteractions(repository);
        verify(scanLock, never()).unlock(anyString(), anyString());
        assertThat(counter("lock_error")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("抢到锁：返回置空+删除行数、success 指标、行数与耗时计时器")
    void runsWhenLockFree() {
        when(scanLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(repository.expireLive(any(LocalDateTime.class), anyInt())).thenReturn(3, 0);
        when(repository.deleteExpired(any(LocalDateTime.class), anyInt())).thenReturn(2, 0);

        assertThat(service.runOnce()).isEqualTo(5);

        verify(scanLock).unlock(eq(LOCK_KEY), anyString());
        assertThat(counter("success")).isEqualTo(1.0);
        assertThat(meterRegistry.get("pricing_snapshot_cleanup_rows_total")
                .tag("action", "expire").counter().count()).isEqualTo(3.0);
        assertThat(meterRegistry.get("pricing_snapshot_cleanup_rows_total")
                .tag("action", "delete").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("pricing_snapshot_cleanup_duration_seconds")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("清理失败：返回 -1、failed 指标 +1、锁仍被释放")
    void returnsMinusOneAndUnlocksOnFailure() {
        when(scanLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(repository.expireLive(any(LocalDateTime.class), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertThat(service.runOnce()).isEqualTo(-1);

        verify(scanLock).unlock(eq(LOCK_KEY), anyString());
        assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("手动 cleanup() 不加锁：直接清理，不碰锁")
    void cleanupStaysLockFree() {
        when(repository.expireLive(any(LocalDateTime.class), anyInt())).thenReturn(0);
        when(repository.deleteExpired(any(LocalDateTime.class), anyInt())).thenReturn(0);

        service.cleanup();

        verifyNoInteractions(scanLock);
        verify(repository).expireLive(any(LocalDateTime.class), anyInt());
    }

    private double counter(String result) {
        return meterRegistry.get("pricing_snapshot_cleanup_total")
                .tag("result", result).counter().count();
    }
}
