package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    private com.newcoupon.infrastructure.persistence.mapper.TemplateMapper templateMapper;
    @Mock
    private StockBucketRouter stockBucketRouter;

    private StockReconcileService forceService;   // force 模式
    private StockReconcileService shadowService;  // 影子模式

    @BeforeEach
    void setUp() {
        forceService = new StockReconcileService(templateRepository, userCouponRepository,
                couponUsageRepository, stockGateway, driftLog, scanLock, stockBucketMapper, templateMapper,
                stockBucketRouter, 1000L, 0L, "force", 500);
        shadowService = new StockReconcileService(templateRepository, userCouponRepository,
                couponUsageRepository, stockGateway, driftLog, scanLock, stockBucketMapper, templateMapper,
                stockBucketRouter, 1000L, 0L, "shadow", 500);
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
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(100L);   // E2b 锚（镜像口径）

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();

        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("三步确认·步1（快照旧）：首读破缺但新鲜 Σquota 追平——止步不升级")
    void threeStep_staleSnapshot_stops() {
        // 列表循环里的旧 quotaSum=97（gc 侧 alloc=100），但主键新鲜重读=100——E2b 只是快照旧
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(90, 2, 8, 0));   // Σ=100=alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(97L, 100L);                 // 第一次(列表)97，新鲜重读100
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(97L, 100L);   // E2b 锚（镜像口径）

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();
        verify(driftLog, never()).record(any());
    }

    @Test
    @DisplayName("三步确认·真漂移：两次读数均破缺——亚秒定罪+提交 deep（不再等 60 秒轮）")
    void threeStep_genuineDrift_confirmsInline() {
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(89, 0, 8, 0));   // Σ=97≠alloc=100
        when(stockBucketMapper.sumQuota(1L)).thenReturn(100L);                       // E2b 平——破缺在 E2a
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(100L);   // E2b 锚（镜像口径）

        assertThat(forceService.conservationCheckOne(tpl(8L))).isTrue();            // 当场定罪→deep
        // 良性方向（Σ<alloc：钱被锁住）——不冻结
        verify(stockGateway, never()).freeze(anyLong(), anyString());
    }

    @Test
    @DisplayName("三步确认·危险方向（Σ>alloc=超发面）：当场自动冻结（2e-②）")
    void threeStep_dangerousDrift_freezesImmediately() {
        // dbi=12 > Σquota 兑现的额度——Redis 比 DB 多钱=超发方向
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(88, 0, 12, 0));  // Σ=100=alloc 但... 
        when(stockBucketMapper.sumQuota(1L)).thenReturn(97L);                       // alloc(100)>Σquota(97)=E2b 危险向
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(97L);   // E2b 锚（镜像口径）

        assertThat(forceService.conservationCheckOne(tpl(8L))).isTrue();
        verify(stockGateway).freeze(1L, RedisStockGateway.FREEZE_SOURCE_AUTO);   // 危险方向：带 auto 来源冻结
    }

    @Test
    @DisplayName("L37·E3a 危险方向（Σquota > allocated=重复建行）：三段确认后当场冻结")
    void e3aQuotaExceedsAllocated_freezes() {
        // Redis 侧与已建行合计一致（E2a/E2b 都平），但与模板行的 allocated 脱节：
        // 已建行合计 100 > 已划出额度 95 —— 同一段额度建了两行 = 超发方向
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(100, 0, 0, 0));   // Σ=100=c:alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(100L);                        // E2b 平
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(100L);   // E2b 锚（镜像口径）
        var po = new com.newcoupon.infrastructure.persistence.po.CouponTemplatePO();
        po.setId(1L);
        po.setAllocated(95L);
        when(templateMapper.selectById(1L)).thenReturn(po);

        assertThat(forceService.conservationCheckOne(tpl(8L))).isTrue();   // 亚秒定罪→deep
        verify(stockGateway).freeze(1L, RedisStockGateway.FREEZE_SOURCE_AUTO);   // 危险方向：带 auto 来源冻结
    }

    @Test
    @DisplayName("L37·E3a 安全方向（Σquota < allocated=已划出未建行）：只告警不冻结")
    void e3aQuotaBelowAllocated_alarmOnly() {
        // 已建行合计 95 < 已划出额度 100 —— 可能是开桶动作正被暂停/补建没跑完，安全方向
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(95, 0, 0, 0));    // Σ=95=c:alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(95L);                         // E2b 平
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(95L);   // E2b 锚（镜像口径）
        var po = new com.newcoupon.infrastructure.persistence.po.CouponTemplatePO();
        po.setId(1L);
        po.setAllocated(100L);
        when(templateMapper.selectById(1L)).thenReturn(po);

        assertThat(forceService.conservationCheckOne(tpl(8L))).isTrue();   // 真破缺→deep 告警
        verify(stockGateway, never()).freeze(anyLong(), anyString());                    // 安全方向：不冻结
    }

    @Test
    @DisplayName("干净轮自动解冻（2e-②通道①）：冻结态+守恒平+无维护标记→自动解")
    void cleanRound_autoUnfreezes() {
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(90, 2, 8, 0));   // Σ=100=alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(100L);
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(100L);   // E2b 锚（镜像口径）
        when(stockGateway.isFrozen(1L)).thenReturn(true);                            // 处于冻结态
        when(stockGateway.isAutoFrozen(1L)).thenReturn(true);                        // 且是自动来源

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();

        verify(stockGateway).unfreeze(1L);   // 自动解冻（只解 auto 来源）
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
        // L44（2026-09-30）：deep 的输入从"活跃行"改为"全世代行"（与 ENSURE 同口径）
        when(stockBucketMapper.selectRegistered(1L)).thenReturn(java.util.List.of(bucketRow(0, 1, 100, 10)));
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
    @DisplayName("批3·L44：deep 全世代输入——封账真代按退役推导（retired=true），且重建成功后覆盖写分配镜像")
    void deepRebuildsSealedGensAndResetsAllocMirror() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(88, 0, 10, 0, false));
        when(userCouponRepository.countByTemplate(1L)).thenReturn(10L);
        when(stockBucketMapper.sumUsed(1L)).thenReturn(10L);
        when(stockGateway.conservationAll(eq(1L), anyLong())).thenReturn(
                new com.newcoupon.infrastructure.redis.RedisStockGateway.GlobalConservation(
                        100, 100, false,
                        java.util.List.of(new com.newcoupon.infrastructure.redis.RedisStockGateway.BucketReading(
                                0, 1, 88, 0, 10, 0))));
        // 全世代行：一行活跃（桶 0/代 1）+ 一行封账真代（桶 1/代 1）
        var sealed = bucketRow(1, 1, 10, 10);
        sealed.setStatus(1);
        when(stockBucketMapper.selectRegistered(1L))
                .thenReturn(java.util.List.of(bucketRow(0, 1, 100, 10), sealed));
        when(stockGateway.rebuildBucket(eq(1L), anyInt(), anyLong(), any(), anyLong(), anyLong(),
                anyBoolean(), anyLong(), anyLong(), anyLong()))
                .thenReturn(new RebuildResult(1, 90, 10, 0));
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(110L);   // 100（活跃）+ 10（封账真代）

        forceService.deepReconcileSync(1L, "test");

        // 活跃行按活跃推导（retired=false），封账行按退役推导（retired=true）——
        // 这钉住的是"改全世代口径后必须传行状态"这个前提（旧代码恒传 false）
        verify(stockGateway).rebuildBucket(eq(1L), eq(0), eq(1L), anyString(), eq(100L), eq(10L),
                eq(false), eq(1000L), eq(10L), eq(0L));
        verify(stockGateway).rebuildBucket(eq(1L), eq(1), eq(1L), anyString(), eq(10L), eq(10L),
                eq(true), eq(1000L), eq(0L), eq(0L));
        // L44 核心：重建全部成功 → 分配镜像按数据库口径覆盖写（修"镜像偏大无自动纠偏"）
        verify(stockGateway).resetAlloc(1L, 110L);
    }

    @Test
    @DisplayName("批3·L44：影子模式绝不覆盖写镜像（影子只算不写）")
    void deepShadowNeverResetsAllocMirror() {
        givenDeepPassGates(tpl(10L));

        shadowService.deepReconcileSync(1L, "test");

        verify(stockGateway, never()).resetAlloc(anyLong(), anyLong());
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
        // L44：deep 的输入改为全世代行（与 ENSURE 同口径）
        when(stockBucketMapper.selectRegistered(1L)).thenReturn(java.util.List.of(bucketRow(0, 1, 100, 10)));
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

    // ==================== L38 补丁（2026-09-27 评审批 1）====================

    @Test
    @DisplayName("L38·部分分配模板（allocated<total）全桶排空：不立售罄牌——还有桶可开（评审 P1-3）")
    void partialAllocation_neverSetsSoldout() {
        // allocated=95 < total=100：桶全空、镜像自洽，但额度没分完——立牌会把 -10 补开路径
        // 短路成终态 3001，剩余 5 张永久不可售
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(0, 0, 95, 0));      // Σ=95=alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(95L);                            // E2b 平
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(95L);   // E2b 锚（镜像口径）
        var po = new com.newcoupon.infrastructure.persistence.po.CouponTemplatePO();
        po.setId(1L);
        po.setAllocated(95L);                                                            // 额度没分完
        when(templateMapper.selectById(1L)).thenReturn(po);

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();                // 干净轮

        verify(stockGateway, never()).setSoldoutWithTtl(anyLong(), anyInt());            // 绝不立牌
    }

    @Test
    @DisplayName("L38·对照：额度已分完（allocated=total）且全桶排空——才允许重立售罄牌")
    void fullAllocation_setsSoldoutWhenAllEmpty() {
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(0, 0, 100, 0));     // Σ=100=alloc
        when(stockGateway.conservationAll(1L, Long.MAX_VALUE)).thenReturn(gc(0, 0, 100, 0));
        when(stockBucketMapper.sumQuota(1L)).thenReturn(100L);
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(100L);   // E2b 锚（镜像口径）
        var po = new com.newcoupon.infrastructure.persistence.po.CouponTemplatePO();
        po.setId(1L);
        po.setAllocated(100L);                                                           // =total
        when(templateMapper.selectById(1L)).thenReturn(po);

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();

        verify(stockGateway).setSoldoutWithTtl(1L, 10);                                  // 真售罄：重立牌
    }

    @Test
    @DisplayName("L38·人工冻结（source=manual）不被干净轮自动解——只解 auto 来源（评审 P2-6/补做 238ff16）")
    void manualFreeze_neverAutoUnfrozen() {
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(90, 2, 8, 0));      // Σ=100=alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(100L);
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(100L);   // E2b 锚（镜像口径）
        when(stockGateway.isFrozen(1L)).thenReturn(true);                                // 处于冻结态
        when(stockGateway.isAutoFrozen(1L)).thenReturn(false);                           // 但来源是人工

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();

        verify(stockGateway, never()).unfreeze(anyLong());                               // 人工冻结：绝不自动解
    }

    // ==================== 批 3（2026-09-28 评审批 3）====================

    /** 注入 aux Redis 桩（心跳/熔断计数/维护标记/修复环计数共用的那个 template） */
    private org.springframework.data.redis.core.StringRedisTemplate auxStub() {
        var aux = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        forceService.wireAuxRedis(aux);
        return aux;
    }

    @Test
    @DisplayName("批3·P2-4：干净轮清零『连续 FORCE』计数（修好一次即打断连续性——旧行为计数只增不清→误熔断）")
    void cleanRound_resetsForceCount() {
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(90, 2, 8, 0));   // Σ=100=alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(100L);
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(100L);
        var aux = auxStub();

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();

        verify(aux).delete("c:frce:{1}");   // 无漂移结论 → 连续计数归零
    }

    @Test
    @DisplayName("批3·P2-4：快照旧止步同样清零（『连续』被任何无漂移结论打断，不止干净轮）")
    void staleSnapshotStop_resetsForceCount() {
        when(stockGateway.conservationAll(1L, 1000L)).thenReturn(gc(90, 2, 8, 0));   // Σ=100=alloc
        when(stockBucketMapper.sumQuota(1L)).thenReturn(97L, 100L);                  // 首次旧值→新鲜重读追平
        when(stockBucketMapper.sumQuotaRegistered(1L)).thenReturn(97L, 100L);
        var aux = auxStub();

        assertThat(forceService.conservationCheckOne(tpl(8L))).isFalse();

        verify(aux).delete("c:frce:{1}");
    }

    @Test
    @DisplayName("批3·P2-5：deep 遇额度段破缺（E3a）只告警——不重建、不记 REPAIRED、不碰熔断计数")
    void deepE3a_alarmOnly() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(tpl(10L)));
        when(stockBucketMapper.sumUsed(1L)).thenReturn(10L);
        when(stockGateway.conservation(1L, 1000L)).thenReturn(reading(90, 0, 10, 0, false));
        when(userCouponRepository.countByTemplate(1L)).thenReturn(10L);
        var aux = auxStub();

        forceService.deepReconcileSync(1L, "conservation(E3a(Σquota=105 ≠ allocated=100))");

        // 额度段脱节 = 行与 allocated 的问题，per-gen REBUILD 修不了（旧行为=照常重建+记 REPAIRED+烧熔断预算）
        verify(stockGateway, never()).rebuildBucket(anyLong(), anyInt(), anyLong(), any(),
                anyLong(), anyLong(), anyBoolean(), anyLong(), anyLong(), anyLong());
        var captor = org.mockito.ArgumentCaptor.forClass(StockDriftLogGateway.DriftEvent.class);
        verify(driftLog).record(captor.capture());
        assertThat(captor.getValue().action()).as("落 E3A_ALARM（不是 REPAIRED）").isEqualTo("E3A_ALARM");
        assertThat(captor.getValue().detail()).contains("E3a(");
        verify(aux, never()).opsForValue();   // 熔断计数完全不碰：不读、不增、不清
    }
}
