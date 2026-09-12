package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.IdentityReading;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 恒等式对账决策逻辑单测（ADR-0008）——mock 存储与网关，逐分支覆盖：
 * 恒等成立 / 首轮疑似 / 连续两轮确认+守卫全过修复 / 复核自愈 / 守卫①在途未清推迟 /
 * 守卫②账本互证失败冻结 / 修复后余量为0不清售罄标志。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("对账·恒等式验账（ADR-0008）")
class StockReconcileServiceTest {

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private RedisStockGateway stockGateway;
    @Mock
    private StockDriftLogGateway driftLog;

    private StockReconcileService service;

    @BeforeEach
    void setUp() {
        service = new StockReconcileService(templateRepository, userCouponRepository,
                stockGateway, driftLog, 1000L, 0L);   // 日志限频 0：每条都落表，便于断言
    }

    /** 模板：total=100，issued 由参数定 */
    private CouponTemplate tpl(long issued) {
        return CouponTemplate.reconstitute(1L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("n")
                        .couponType(CouponType.NO_THRESHOLD).faceValue(500L).threshold(0L)
                        .totalCount(100L).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                issued, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }

    private IdentityReading reading(long issued, long dbi, long pending) {
        return new IdentityReading(issued, dbi, pending);
    }

    @Test
    @DisplayName("恒等式成立（issued−dbi−pend=0）：无事发生，不触碰 Redis")
    void identityHolds() {
        when(stockGateway.identity(1L, 1000L)).thenReturn(reading(10, 8, 2));

        assertThat(service.identityCheckOne(1L)).isFalse();

        verify(stockGateway, never()).repair(anyLong(), anyLong(), anyLong(), anyLong());
        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("首轮差值≠0：只疑似告警，不进第二层（等下轮复核）")
    void firstRoundOnlySuspects() {
        when(stockGateway.identity(1L, 1000L)).thenReturn(reading(11, 10, 0));   // delta=+1

        assertThat(service.identityCheckOne(1L)).isFalse();

        verify(stockGateway, never()).repair(anyLong(), anyLong(), anyLong(), anyLong());
        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("连续两轮差值≠0 + 守卫全过：按 DB 口径重置三本账并落表")
    void confirmedDriftRepairs() {
        when(stockGateway.identity(1L, 1000L)).thenReturn(reading(11, 10, 0));   // delta=+1
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(userCouponRepository.countByTemplate(1L)).thenReturn(10L);         // 守卫②过
        when(stockGateway.remaining(1L)).thenReturn(89L);
        when(stockGateway.repair(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(true);  // P0④：Lua 门放行
        when(driftLog.countSince(anyLong(), any())).thenReturn(0L);

        assertThat(service.identityCheckOne(1L)).isFalse();   // 第1轮：疑似
        assertThat(service.identityCheckOne(1L)).isTrue();    // 第2轮：确认→修复

        verify(stockGateway, times(1)).repair(1L, 90L, 10L, 10L);   // stock=100-10, issued=dbi=10
        verify(stockGateway, times(1)).clearSoldout(1L);            // B4：恢复余量必须清标志
        verify(driftLog, times(1)).record(any());
    }

    @Test
    @DisplayName("P0④回归：修复执行瞬间在途非空（Lua 门拦截）→ 推迟不修、不清售罄标志")
    void repairDeferredByLuaGate() {
        when(stockGateway.identity(1L, 1000L)).thenReturn(reading(11, 10, 0));
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(userCouponRepository.countByTemplate(1L)).thenReturn(10L);
        when(stockGateway.remaining(1L)).thenReturn(89L);
        when(stockGateway.repair(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(false); // 门拦截

        service.identityCheckOne(1L);
        assertThat(service.identityCheckOne(1L)).isTrue();

        verify(driftLog, times(1)).record(any());                       // DEFERRED_PENDING 落表
        verify(stockGateway, never()).clearSoldout(anyLong());          // 没修就不能动标志
    }

    @Test
    @DisplayName("复核自愈（第2轮恒等式归0）：不修复——慢请求 confirm 迟到的核心回归")
    void transientDriftHealsOnSecondRound() {
        when(stockGateway.identity(1L, 1000L))
                .thenReturn(reading(11, 10, 0))    // 第1轮：delta=+1 疑似
                .thenReturn(reading(10, 10, 0));   // 第2轮：confirm 迟到到达，自愈

        assertThat(service.identityCheckOne(1L)).isFalse();
        assertThat(service.identityCheckOne(1L)).isFalse();

        verify(stockGateway, never()).repair(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("守卫①：在途未清空 → 推迟修复（DEFERRED_PENDING），不动三本账")
    void repairDeferredWhilePending() {
        when(stockGateway.identity(1L, 1000L)).thenReturn(reading(12, 10, 1));   // delta=+1 且在途
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(stockGateway.remaining(1L)).thenReturn(89L);

        service.identityCheckOne(1L);
        assertThat(service.identityCheckOne(1L)).isTrue();   // 进第二层

        verify(stockGateway, never()).repair(anyLong(), anyLong(), anyLong(), anyLong());
        verify(driftLog, times(1)).record(any());
    }

    @Test
    @DisplayName("守卫②：issued_count ≠ COUNT(user_coupon) → 账本自相矛盾，冻结修复（GUARD_FAILED）")
    void guardFailureFreezesRepair() {
        when(stockGateway.identity(1L, 1000L)).thenReturn(reading(11, 10, 0));
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(userCouponRepository.countByTemplate(1L)).thenReturn(9L);   // 账本说10张，明细只有9行
        when(stockGateway.remaining(1L)).thenReturn(89L);

        service.identityCheckOne(1L);
        assertThat(service.identityCheckOne(1L)).isTrue();

        verify(stockGateway, never()).repair(anyLong(), anyLong(), anyLong(), anyLong());
        verify(driftLog, times(1)).record(any());   // 留痕 + 告警，人工介入
    }

    @Test
    @DisplayName("修复后余量为0（售罄态修复）：不清售罄标志——B4 清除协议的反向边界")
    void repairToZeroKeepsSoldoutFlag() {
        when(stockGateway.identity(1L, 1000L)).thenReturn(reading(101, 100, 0));
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(100L)));   // total=100 全发完
        when(userCouponRepository.countByTemplate(1L)).thenReturn(100L);
        when(stockGateway.remaining(1L)).thenReturn(0L);
        when(stockGateway.repair(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(true);
        when(driftLog.countSince(anyLong(), any())).thenReturn(0L);

        service.identityCheckOne(1L);
        assertThat(service.identityCheckOne(1L)).isTrue();

        verify(stockGateway, times(1)).repair(1L, 0L, 100L, 100L);
        verify(stockGateway, never()).clearSoldout(anyLong());   // 余量0：标志必须留着短路流量
    }

    @Test
    @DisplayName("第二层复测自愈（疑似到确认的间隙里慢请求 confirm 到达）：不修复")
    void deepReconcileRecheckHeals() {
        when(stockGateway.identity(1L, 1000L))
                .thenReturn(reading(11, 10, 0))    // 第1轮疑似
                .thenReturn(reading(12, 10, 0))    // 第2轮确认（触发 deep）
                .thenReturn(reading(10, 10, 0));   // deep 复测：自愈了
        service.identityCheckOne(1L);
        service.identityCheckOne(1L);

        verify(stockGateway, never()).repair(anyLong(), anyLong(), anyLong(), anyLong());
        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("懒重建走条件 SET（-7 路径）：以 DB 口径计算，由 Lua 保证只生效一次")
    void lazyRebuildUsesConditionalSet() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));

        service.lazyRebuildStock(1L);

        verify(stockGateway, times(1)).lazyRebuildStock(1L, 90L);
        verify(stockGateway, never()).initStock(anyLong(), anyLong());   // 懒重建不再无条件 SET
    }
}
