package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.ConservationReading;
import com.newcoupon.infrastructure.redis.RedisStockGateway.RebuildResult;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 守恒对账决策逻辑单测（ADR-0009，2026-09-16 重写——前身恒等式版随范式退役）。
 * 逐分支覆盖：守恒成立 / 首轮疑似 / 两轮确认提交 deep / 镜像幻影臂（陈旧 ic 不告警、
 * 新鲜 ic 才告警）/ confirm-lost 臂（方向性判据）/ deep 白名单逐关 / FORCE 分支推导 /
 * 影子模式不 SET / 栅栏中止 / 在途推迟 / 账实矛盾冻结 / 不可领只告警 / 不可能态 / 囚禁。
 * deep 直调 deepReconcileSync（同步版），避免异步执行器在单测里的时序问题。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("对账·守恒式验账（ADR-0009）")
class StockReconcileServiceTest {

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private com.newcoupon.domain.repository.CouponUsageRepository couponUsageRepository;
    @Mock
    private RedisStockGateway stockGateway;
    @Mock
    private StockDriftLogGateway driftLog;
    @Mock
    private RedisScanLock scanLock;
    @Mock
    private com.newcoupon.infrastructure.persistence.mapper.StockBucketMapper stockBucketMapper;
    @Mock
    private StockBucketRouter stockBucketRouter;

    private StockReconcileService forceService;   // force 模式
    private StockReconcileService shadowService;  // 影子模式

    @BeforeEach
    void setUp() {
        forceService = new StockReconcileService(templateRepository, userCouponRepository,
                couponUsageRepository, stockGateway, driftLog, scanLock, stockBucketMapper, stockBucketRouter,
                1000L, 0L, "force", 500);
        shadowService = new StockReconcileService(templateRepository, userCouponRepository,
                couponUsageRepository, stockGateway, driftLog, scanLock, stockBucketMapper, stockBucketRouter,
                1000L, 0L, "shadow", 500);
    }

    /** 模板：total=100，issued 由参数定（可领态） */
    private CouponTemplate tpl(long issued) {
        return CouponTemplate.reconstitute(1L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("n")
                        .couponType(CouponType.NO_THRESHOLD).faceValue(500L).threshold(0L)
                        .totalCount(100L).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                issued, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }

    /** 不可领模板（已下线——白名单②不过） */
    private CouponTemplate offlineTpl(long issued) {
        return CouponTemplate.reconstitute(1L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("n")
                        .couponType(CouponType.NO_THRESHOLD).faceValue(500L).threshold(0L)
                        .totalCount(100L).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                issued, ApproveStatus.APPROVED, PublishStatus.OFFLINE);
    }

    private ConservationReading reading(long stk, long pend, long dbi, long voidR, boolean so) {
        return new ConservationReading(stk, pend, dbi, voidR, so);
    }

    /** P2b E2 两段式桩：由聚合读数构造 GlobalConservation（Σ四键=alloc=total 时双平） */
    private com.newcoupon.infrastructure.redis.RedisStockGateway.GlobalConservation gc(long stk, long pend,
                                                                                       long dbi, long voidR) {
        long sum = stk + pend + dbi + voidR;
        return new com.newcoupon.infrastructure.redis.RedisStockGateway.GlobalConservation(
                sum, sum, false,
                java.util.List.of(new com.newcoupon.infrastructure.redis.RedisStockGateway.BucketReading(
                        0, 1, stk, pend, dbi, voidR)));
    }

    // ==================== 巡检：守恒式两轮确认 ====================


    @Test
    @DisplayName("守恒成立（E2a+E2b 双平）：无事发生")
    void conservationHolds() {
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(90, 2, 8, 0));   // Σ=100=alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(100L);                       // E2b 平

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();

        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("三步确认·步1（快照旧）：首读破缺但新鲜 Σquota 追平——止步不升级")
    void threeStep_staleSnapshot_stops() {
        // 列表循环里的旧 quotaSum=97（gc 侧 alloc=100），但主键新鲜重读=100——E2b 只是快照旧
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(90, 2, 8, 0));   // Σ=100=alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(97L, 100L);                 // 第一次(列表)97，新鲜重读100

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();
        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("三步确认·真漂移：两次读数均破缺——亚秒定罪+提交 deep（不再等 60 秒轮）")
    void threeStep_genuineDrift_confirmsInline() {
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(89, 0, 8, 0));   // Σ=97≠alloc=100
        when(stockBucketMapper.sumQuota(1L)).thenReturn(100L);                       // E2b 平——破缺在 E2a

        assertThat(forceService.conservationCheckOne(tpl(8L))).isTrue();            // 当场定罪→deep
        // 良性方向（Σ<alloc：钱被锁住）——不冻结
        verify(stockGateway, never()).freeze(anyLong());
    }

    @Test
    @DisplayName("三步确认·危险方向（Σ>alloc=超发面）：当场自动冻结（2e-②）")
    void threeStep_dangerousDrift_freezesImmediately() {
        // dbi=12 > Σquota 兑现的额度——Redis 比 DB 多钱=超发方向
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(88, 0, 12, 0));  // Σ=100=alloc 但... 
        when(stockBucketMapper.sumQuota(1L)).thenReturn(97L);                       // alloc(100)>Σquota(97)=E2b 危险向

        assertThat(forceService.conservationCheckOne(tpl(8L))).isTrue();
        verify(stockGateway).freeze(1L);   // 危险方向：当场冻结（后续轮干净→自动解）
    }

    @Test
    @DisplayName("干净轮自动解冻（2e-②通道①）：冻结态+守恒平+无维护标记→自动解")
    void cleanRound_autoUnfreezes() {
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(90, 2, 8, 0));   // Σ=100=alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(100L);
        when(stockGateway.isFrozen(1L)).thenReturn(true);                            // 处于冻结态

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();

        verify(stockGateway).unfreeze(1L);   // 自动解冻
    }

    // ==================== 镜像：方向性两臂 ====================

    @Test
    @DisplayName("镜像·幻影臂：dbi>列表ic 但新鲜ic 追平——陈旧列表不告警（P0-① 零误报）")
    void phantomArmStaleListIcNoAlarm() {
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(90, 0, 11, 0, false));   // dbi=11
        // P2b E3c：镜像锚=sumUsed。首读 10（陈旧），主键复读 11 追平 → 不告警
        when(stockBucketMapper.sumUsedBucketed(1L)).thenReturn(10L, 11L);

        assertThat(forceService.mirrorCheck(tpl(10L))).isFalse();   // 列表 ic=10（陈旧）

        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("镜像·幻影臂：dbi>新鲜ic——真幻影（抹掉已提交事务），立即告警")
    void phantomArmFreshIcAlarms() {
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(89, 0, 11, 0, false));
        when(stockBucketMapper.sumUsedBucketed(1L)).thenReturn(10L, 10L);

        assertThat(forceService.mirrorCheck(tpl(10L))).isTrue();

        verify(driftLog).record(any());
    }

    @Test
    @DisplayName("镜像·confirm-lost 臂：gap>0 且 pend==0 → deep；pend>0 时不动作（方向性判据）")
    void confirmLostArmDirectional() {
        // pend>0：在途解释了 gap——不动作（流量期告警永动的解药）
        when(stockBucketMapper.sumUsedBucketed(1L)).thenReturn(10L);   // P2b：镜像锚（dbi=8，gap=2）
        when(stockBucketMapper.sumUsedBucketed(1L)).thenReturn(10L);
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(89, 2, 8, 0, false));   // gap=2=pend
        assertThat(forceService.mirrorCheck(tpl(10L))).isFalse();

        // pend==0 且 gap>0：confirm 丢失——deep
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(90, 0, 8, 0, false));   // gap=2, pend=0
        assertThat(forceService.mirrorCheck(tpl(10L))).isTrue();
    }

    // ==================== deep：白名单与分支推导 ====================

    private void givenDeepPassGates(CouponTemplate t) {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(t));
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(88, 0, 10, 0, false));
        when(userCouponRepository.countByTemplate(1L)).thenReturn(t.getIssuedCount());
        when(stockBucketMapper.sumUsed(1L)).thenReturn(t.getIssuedCount());   // P2b：守卫④/deep ic 换锚
        // P2b 分代桥接路径：DB 行 + 分代读数 + 分代重建
        when(stockBucketMapper.selectActive(1L)).thenReturn(java.util.List.of(bucketRow(0, 1, 100, 10)));
        when(stockGateway.conservationAll(eq(1L), anyLong())).thenReturn(
                new com.newcoupon.infrastructure.redis.RedisStockGateway.GlobalConservation(
                        100, 100, false,
                        java.util.List.of(new com.newcoupon.infrastructure.redis.RedisStockGateway.BucketReading(
                                0, 1, 88, 0, 10, 0))));
        when(stockGateway.rebuildBucket(eq(1L), anyInt(), anyLong(), any(), anyLong(), anyLong(),
                anyBoolean(), anyLong(), anyLong(), anyLong()))
                .thenReturn(new RebuildResult(1, 90, 10, 0));
    }

    private com.newcoupon.infrastructure.persistence.po.StockBucketPO bucketRow(int b, int g, long quota, long used) {
        com.newcoupon.infrastructure.persistence.po.StockBucketPO po =
                new com.newcoupon.infrastructure.persistence.po.StockBucketPO();
        po.setTemplateId(1L);
        po.setBucketNo(b);
        po.setGeneration(g);
        po.setQuota(quota);
        po.setUsed(used);
        po.setStatus(0);
        return po;
    }

    @Test
    @DisplayName("deep·白名单全过（force）：按业务状态推导重建+清 so+落表 REPAIRED")
    void deepForceRepairs() {
        givenDeepPassGates(tpl(10L));

        forceService.deepReconcileSync(1L, "test");

        verify(stockGateway).rebuildBucket(eq(1L), anyInt(), anyLong(), eq("force"),
                eq(100L), eq(10L), anyBoolean(), eq(1000L), eq(10L), eq(0L));
        verify(stockGateway).clearSoldout(1L);   // B4：修复恢复余量必清标志
        verify(driftLog).record(any());
    }

    @Test
    @DisplayName("deep·影子模式：推导落表但 mode=shadow（不执行 SET）")
    void deepShadowDerivesOnly() {
        givenDeepPassGates(tpl(10L));

        shadowService.deepReconcileSync(1L, "test");

        verify(stockGateway).rebuildBucket(eq(1L), anyInt(), anyLong(), eq("shadow"),
                anyLong(), anyLong(), anyBoolean(), anyLong(), anyLong(), anyLong());
        verify(stockGateway, never()).clearSoldout(anyLong());
    }

    @Test
    @DisplayName("deep·白名单②不可领模板：破缺只告警（ALARM_ONLY），不修复")
    void deepNotReceivableAlarmsOnly() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(offlineTpl(10L)));
        when(stockBucketMapper.sumUsed(1L)).thenReturn(10L);
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(50, 0, 10, 0, false));   // sum=60≠100

        forceService.deepReconcileSync(1L, "test");

        verify(stockGateway, never()).rebuildBucket(anyLong(), anyInt(), anyLong(), any(),
                anyLong(), anyLong(), anyBoolean(), anyLong(), anyLong(), anyLong());
        verify(driftLog).record(any());
    }

    @Test
    @DisplayName("deep·在途未清：DEFERRED（修复会覆盖在途记账——推迟到下轮）")
    void deepPendingDefers() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(stockBucketMapper.sumUsed(1L)).thenReturn(10L);
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(88, 2, 10, 0, false));   // pend=2

        forceService.deepReconcileSync(1L, "test");

        verify(stockGateway, never()).rebuildBucket(anyLong(), anyInt(), anyLong(), any(),
                anyLong(), anyLong(), anyBoolean(), anyLong(), anyLong(), anyLong());
        verify(driftLog).record(any());
    }

    @Test
    @DisplayName("deep·账实矛盾（ic≠COUNT）：GUARD_FAILED 冻结一切自动动作（修复失去基准）")
    void deepGuardFailedFreezes() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(stockBucketMapper.sumUsed(1L)).thenReturn(10L);
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(88, 0, 10, 0, false));
        when(userCouponRepository.countByTemplate(1L)).thenReturn(9L);   // ≠ SUM(used)=10 → GUARD_FAILED

        forceService.deepReconcileSync(1L, "test");

        verify(stockGateway, never()).rebuildBucket(anyLong(), anyInt(), anyLong(), any(),
                anyLong(), anyLong(), anyBoolean(), anyLong(), anyLong(), anyLong());
        verify(driftLog).record(any());
    }

    @Test
    @DisplayName("deep·Lua 栅栏中止（-2）：BARRIER_ABORT 落表（读数与执行间混入写）")
    void deepBarrierAborts() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(stockBucketMapper.sumUsed(1L)).thenReturn(10L);
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(88, 0, 10, 0, false));
        when(userCouponRepository.countByTemplate(1L)).thenReturn(10L);
        when(stockBucketMapper.selectActive(1L)).thenReturn(java.util.List.of(bucketRow(0, 1, 100, 10)));
        when(stockGateway.conservationAll(eq(1L), anyLong())).thenReturn(
                new com.newcoupon.infrastructure.redis.RedisStockGateway.GlobalConservation(
                        100, 100, false,
                        java.util.List.of(new com.newcoupon.infrastructure.redis.RedisStockGateway.BucketReading(
                                0, 1, 88, 0, 10, 0))));
        when(stockGateway.rebuildBucket(eq(1L), anyInt(), anyLong(), any(), anyLong(), anyLong(),
                anyBoolean(), anyLong(), anyLong(), anyLong()))
                .thenReturn(new RebuildResult(-2, 0, 0, 0));

        forceService.deepReconcileSync(1L, "test");

        verify(stockGateway, never()).clearSoldout(anyLong());
        verify(driftLog).record(any());
    }

    @Test
    @DisplayName("deep·不可能态（stk+dbi>total）：只告警（total 被调小/void 异常）")
    void deepImpossibleStateAlarms() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(stockBucketMapper.sumUsed(1L)).thenReturn(10L);
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(95, 0, 10, 0, false));   // 95+10>100
        when(userCouponRepository.countByTemplate(1L)).thenReturn(10L);

        forceService.deepReconcileSync(1L, "test");

        verify(stockGateway, never()).rebuildBucket(anyLong(), anyInt(), anyLong(), any(),
                anyLong(), anyLong(), anyBoolean(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("deep·囚禁告警：stk=0 无 so 且 dbi+void=total 且 dbi<total（重新上线未走协议）")
    void deepImprisonedAlarms() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(stockBucketMapper.sumUsed(1L)).thenReturn(10L);
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(0, 0, 10, 90, false));   // 0+10+90=100
        when(userCouponRepository.countByTemplate(1L)).thenReturn(10L);

        forceService.deepReconcileSync(1L, "test");

        verify(stockGateway, never()).rebuildBucket(anyLong(), anyInt(), anyLong(), any(),
                anyLong(), anyLong(), anyBoolean(), anyLong(), anyLong(), anyLong());
        verify(driftLog).record(any());
    }
}
