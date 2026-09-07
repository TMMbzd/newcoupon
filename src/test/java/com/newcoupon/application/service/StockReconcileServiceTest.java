package com.newcoupon.application.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 对账决策逻辑单测（ADR-0007 / L21 修复的分支覆盖）——mock 存储，直接 new 计数器，
 * recheckMillis=0 让二次复核即时完成。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("对账·在途治理（L21修复）")
class StockReconcileServiceTest {

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private RedisStockGateway stockGateway;

    private final ReceiveInFlightCounter counter = new ReceiveInFlightCounter();

    private StockReconcileService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new StockReconcileService(templateRepository, stockGateway, counter, 0L);
    }

    /** 模板：total=100，issued 由参数定 → expected = 100-issued */
    private CouponTemplate tpl(long issued) {
        return CouponTemplate.reconstitute(1L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("n")
                        .couponType(CouponType.NO_THRESHOLD).faceValue(500L).threshold(0L)
                        .totalCount(100L).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                issued, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }

    private void given(long issued, Long redisRemaining) {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(issued)));
        when(stockGateway.remaining(1L)).thenReturn(redisRemaining);
    }

    @Test
    @DisplayName("在途窗口内（diff=在途数）：一致，不重建、Redis 不被触碰")
    void inFlightIsNotDrift() {
        given(10L, 85L);        // expected=90, redis=85, diff=5
        counter.set(5);         // 5个在途 ⇒ diff ≤ 5+5 余量 ⇒ 正常

        boolean rebuilt = service.reconcileOne(1L);

        assertThat(rebuilt).isFalse();
        verify(stockGateway, never()).initStock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("超在途+余量且复核仍在（真漂移）：二次量测后重建一次")
    void realDriftRebuildsAfterRecheck() {
        given(10L, 40L);        // expected=90, redis=40, diff=50 ≫ 在途0+5

        boolean rebuilt = service.reconcileOne(1L);

        assertThat(rebuilt).isTrue();
        verify(templateRepository, times(3)).findById(1L);   // 首量+复核+重建内部读取
        verify(stockGateway, times(1)).initStock(1L, 90L);   // 以账本重建
    }

    @Test
    @DisplayName("瞬态差异（首量超界、复核时自愈）：不重建——L21 的核心回归")
    void transientDiffHealsOnRecheck() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));  // expected=90
        when(stockGateway.remaining(1L)).thenReturn(70L, 90L);   // 首量70(diff=20)→复核90(一致)

        boolean rebuilt = service.reconcileOne(1L);

        assertThat(rebuilt).isFalse();
        verify(stockGateway, never()).initStock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Redis 偏高（diff<0，无任何合法在途解释）：复核仍在 → 重建")
    void redisTooHighAlwaysAbnormal() {
        given(10L, 95L);        // expected=90, redis=95, diff=-5

        boolean rebuilt = service.reconcileOne(1L);

        assertThat(rebuilt).isTrue();
        verify(stockGateway, times(1)).initStock(1L, 90L);
    }

    @Test
    @DisplayName("key 缺失：无在途歧义，立即重建（不进复核等待）")
    void keyMissingRebuildsImmediately() {
        given(10L, null);

        boolean rebuilt = service.reconcileOne(1L);

        assertThat(rebuilt).isTrue();
        verify(stockGateway, times(1)).initStock(1L, 90L);
        verify(templateRepository, times(2)).findById(1L);    // 量测1次+重建内部读取（无复核等待）
    }
}
