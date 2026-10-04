package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.CancelCause;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductResult;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 领券编排层全分支测试（ADR-0006 + ADR-0008 记账形态）。
 * 关键回归：A1' 分类先行——DB 拒绝后先取新快照分类，再按分类选取消变体
 * （售罄=钉零+立标志；下线=钉零；瞬时=回加），顺序与形态都断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("领券编排层·Redis闸门+记账三变体")
class ReceiveAppServiceTest {

    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private CouponIssueTxService issueTxService;
    @Mock
    private RedisStockGateway stockGateway;
    @Mock
    private StockReconcileService reconcileService;
    @Mock
    private com.newcoupon.infrastructure.persistence.StockDriftLogGateway driftLog;
    @Mock
    private com.newcoupon.application.service.StockBucketRouter stockBucketRouter;
    @Mock
    private CachedTemplateReader cachedTemplateReader;
    @Mock
    private ReceiveMetrics metrics;
    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Mock
    private com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers breakers;

    /**
     * 让熔断器变成"透传"：CLOSED 状态的熔断器会直接执行被装饰的调用，等价于"没有熔断"。
     *
     * <p>本类测的是**编排逻辑**（闸门结果分类、补偿三变体、重试），熔断行为由
     * DependencyCircuitBreakerIntegrationTest / TenantCircuitBreakerIntegrationTest 专门覆盖。
     *
     * <p>用 lenient 是因为部分用例在更早的阶段就返回、根本走不到依赖调用
     * （严格桩检查会把"没用上"当成错误）。
     *
     * <p><b>补这个 mock 的教训</b>：第 7 步给 ReceiveAppService 加了构造依赖时，
     * 我只跑了集成测试、**漏了单测** → 本类 20 例里 19 例 NPE 却没人发现，
     * 直到第 9 步的多 agent 审查（风险视角）预言"@InjectMocks 不会自动补新依赖"才回头验证。
     * **加构造依赖后必须同时跑单测与集成测试。**
     */
    @org.junit.jupiter.api.BeforeEach
    void stubBreakersAsPassThrough() {
        io.github.resilience4j.circuitbreaker.CircuitBreaker passThrough =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("test");
        org.mockito.Mockito.lenient()
                .when(breakers.dependency(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(passThrough);
        org.mockito.Mockito.lenient()
                .when(breakers.forTenant(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(passThrough);
    }

    @org.junit.jupiter.api.BeforeEach
    void readerPassthrough() {
        // 入口读模板已走缓存（P2c）——让 mock 的 reader 透传给 mock 的 repository，
        // 下方所有 findById 桩（含多段序列桩）原样生效；分类路径的直连桩不受影响
        org.mockito.Mockito.when(cachedTemplateReader.get(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> templateRepository.findById(inv.getArgument(0)));
    }

    @InjectMocks
    private ReceiveAppService service;

    private static CouponTemplate template(int perUserLimit) {
        return CouponTemplate.reconstitute(1L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("满100减10")
                        .couponType(CouponType.FULL_REDUCTION)
                        .faceValue(1000L).threshold(10000L).totalCount(100L)
                        .perUserLimit(perUserLimit)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                10L, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }

    private static CouponTemplate tplWithStatus(long issued, ApproveStatus approve, PublishStatus publish) {
        return CouponTemplate.reconstitute(1L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("满100减10")
                        .couponType(CouponType.FULL_REDUCTION)
                        .faceValue(1000L).threshold(10000L).totalCount(100L).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                issued, approve, publish);
    }

    private static UserCoupon coupon(long templateId, int seq) {
        return UserCoupon.reconstitute(5L, 42L, templateId, "T1", 1000L, 10000L, null,
                LocalDateTime.of(2026, 9, 14, 23, 59, 59), CouponStatus.USABLE,
                LocalDateTime.now(), "req-1", seq);
    }

    private void givenTemplateAndGate(int perUserLimit, DeductResult gate) {
        when(templateRepository.findById(anyLong())).thenReturn(Optional.of(template(perUserLimit)));
        when(stockGateway.deduct(any())).thenReturn(gate);
    }

    // ---------- S2 第 9 步：时间预算裁剪 ----------
    //
    // 这两条用例是**对抗性审查要求补的**：原来只有集成用例，而集成用例里
    // "准入门拦下"与"写地板先补偿再拒"都表现为"5003 + 没发券"，**删掉准入门它们照样绿**——
    // 也就是说准入门（本步的第一根支柱）当时是没人守的代码。用 mock 才能把两者区分开。

    @Test
    @DisplayName("准入门：预算已耗尽 → 抛 5003，且**一个依赖调用都没发**")
    void expiredBudget_rejectsBeforeAnyDependencyCall() {
        when(templateRepository.findById(anyLong())).thenReturn(Optional.of(template(1)));
        // appId 用 "1"：与模板默认归属（内置应用）一致，保证拦下它的是准入门而不是越权检查
        RequestContext.set(new RequestContext("1", "trace", System.currentTimeMillis() - 60_000, true));
        try {
            assertThatThrownBy(() -> service.receive(new ReceiveRequest("ddl-1", 42L, 1L)))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode().getCode())
                    .isEqualTo(5003);

            org.mockito.Mockito.verifyNoInteractions(stockGateway);
            org.mockito.Mockito.verifyNoInteractions(issueTxService);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("写地板：扣减成功但预算不足 → **先补偿再拒**，不落库")
    void writeFloor_cancelsThenRejects() {
        givenTemplateAndGate(5, new DeductResult(0, 1));   // 闸门放行
        // 预算 400ms：准入门放行（> 0），写地板拦下（< WRITE_FLOOR_MS = 500）
        RequestContext.set(new RequestContext("1", "trace", System.currentTimeMillis() + 400, true));
        try {
            assertThatThrownBy(() -> service.receive(new ReceiveRequest("ddl-wf", 42L, 1L)))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode().getCode())
                    .isEqualTo(5003);

            // cancel 的签名是 (cmd, bucket, gen, cause)——用参数匹配器跟上真实形态
            org.mockito.Mockito.verify(stockGateway).cancel(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(issueTxService, org.mockito.Mockito.never())
                    .issue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        } finally {
            RequestContext.clear();
        }
    }

    // ---------- 快查与闸门前校验 ----------

    @Test
    @DisplayName("Redis幂等命中(-8)同模板 → DB确认 → duplicated=true，不进事务")
    void gateIdemHitSameTemplate() {
        givenTemplateAndGate(1, new DeductResult(-8, 0));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();
        verifyNoInteractions(issueTxService);
    }

    @Test
    @DisplayName("Redis幂等命中(-8)但换模板 → DB确认后 1001 拒绝（requestId绑定领取意图）")
    void gateIdemHitDifferentTemplateRejected() {
        givenTemplateAndGate(1, new DeductResult(-8, 0));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 2L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
    }

    @Test
    @DisplayName("入口不再打DB快查：首次领取路径零前置DB幂等查询（评审修正的回归）")
    void noUpfrontDbIdempotentSelect() {
        givenTemplateAndGate(1, new DeductResult(-3, 0));   // 直接售罄
        when(userCouponRepository.findByIdempotentKey(42L, "req-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .isInstanceOf(BizException.class);
        // 闸门拒绝后的陈旧重放检查只查一次；若入口还有快查，这里会是两次
        verify(userCouponRepository, org.mockito.Mockito.times(1)).findByIdempotentKey(42L, "req-1");
    }

    @Test
    @DisplayName("模板不存在 → 2001（闸门前拦截）")
    void templateMissing() {
        when(cachedTemplateReader.get(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND);
    }

    // ---------- 闸门拒绝分支 ----------

    @Test
    @DisplayName("闸门-3售罄（无陈旧重放）→ 3001")
    void gateSoldOut() {
        givenTemplateAndGate(1, new DeductResult(-3, 0));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_OUT);
    }

    @Test
    @DisplayName("闸门-2超领，但DB幂等命中 → 陈旧重放（idem键TTL过期）→ duplicated=true")
    void gateLimitButStaleReplay() {
        givenTemplateAndGate(1, new DeductResult(-2, 0));
        // 入口无快查，闸门拒绝后的"陈旧重放检查"是唯一一次DB查询，直接命中
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();
    }

    @Test
    @DisplayName("闸门-8但DB未查到（并发在途：赢家未提交）→ 1001 处理中提示")
    void gateIdemHitButInFlight() {
        givenTemplateAndGate(1, new DeductResult(-8, 0));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        verifyNoInteractions(issueTxService);
    }

    @Test
    @DisplayName("闸门-7未初始化 → 退避+懒重建（条件SET）后重试成功")
    void gateUninitializedRebuildsAndRetries() {
        when(cachedTemplateReader.get(1L)).thenReturn(Optional.of(template(1)));
        when(stockGateway.deduct(any()))
                .thenReturn(new DeductResult(-7, 0))
                .thenReturn(new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), eq(1L), anyInt(), anyLong(), any())).thenReturn(coupon(1L, 1));
        when(stockBucketRouter.ensureRegisteredBuckets(1L)).thenReturn(true);

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isFalse();
        verify(stockBucketRouter).ensureRegisteredBuckets(1L);   // P2b：ENSURE 按 DB 桶行重建（批 2：全世代口径）
    }

    // ---------- 事务成功与失败取消记账 ----------

    @Test
    @DisplayName("闸门过+事务成功 → confirm记账（dbi+1与令牌出清），绝不取消")
    void happyPathConfirms() {
        givenTemplateAndGate(3, new DeductResult(0, 2));
        when(issueTxService.issue(any(), any(), eq(2L), anyInt(), anyLong(), any())).thenReturn(coupon(1L, 2));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isFalse();
        verify(stockGateway).confirm(any(), anyInt(), anyLong());                              // 记账确认
        verify(stockGateway, never()).cancel(any(), anyInt(), anyLong(), any());               // 成功路径无取消
    }

    @Test
    @DisplayName("事务DuplicateKey → cancel(TRANSIENT) + 分类为幂等成功（uk_idem）")
    void duplicateKeyCancelsTransientAndClassifies() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new DuplicateKeyException("uk_idem"));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();
        verify(stockGateway).cancel(any(), anyInt(), anyLong(), eq(CancelCause.TRANSIENT));   // 幂等冲突是瞬时类：回加
    }

    @Test
    @DisplayName("事务DuplicateKey 且非幂等键、DB行数已达限 → 3002 + cancel(TRANSIENT) + 计数自愈（L3）")
    void duplicateKeySeqConflictIsUserLimit() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new DuplicateKeyException("uk_user_seq"));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.empty());
        // L3：DB 权威行数=1（限领1）→ 真达限：重置计数+正确拒绝，不透明重试
        when(userCouponRepository.countByUserAndTemplate(42L, 1L)).thenReturn(1L);
        when(userCouponRepository.maxSeqByUserAndTemplate(42L, 1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_LIMIT_EXCEEDED);
        verify(stockGateway).cancel(any(), anyInt(), anyLong(), eq(CancelCause.TRANSIENT));
        verify(stockGateway).setUserCount(1L, 42L, 1L);   // 拒绝的同时计数已自愈
    }

    @Test
    @DisplayName("事务DuplicateKey 但 DB行数未达限 → 计数自愈+透明重试一次（L3：失忆非超领）")
    void duplicateKeySeqConflictSelfHealsAndRetries() {
        givenTemplateAndGate(3, new DeductResult(0, 1));
        // 第一次 issue 撞序号索引（失忆的表征），重试（自愈后）成功
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new DuplicateKeyException("uk_user_seq"))
                .thenReturn(com.newcoupon.domain.coupon.UserCoupon.reconstitute(
                        9L, 42L, 1L, "T1", 1000L, 10000L, null, null,
                        com.newcoupon.domain.coupon.CouponStatus.USABLE, null, "req-1", 3));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.empty());
        when(userCouponRepository.countByUserAndTemplate(42L, 1L)).thenReturn(2L);   // 2/3：失忆
        when(userCouponRepository.maxSeqByUserAndTemplate(42L, 1L)).thenReturn(2L);

        var r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        org.assertj.core.api.Assertions.assertThat(r.duplicated()).isFalse();
        verify(stockGateway).setUserCount(1L, 42L, 2L);   // 自愈为 DB 行数
    }

    @Test
    @DisplayName("DB拒绝·新快照=售罄 → cancel(SOLD_OUT)钉零+立标志（A1'核心：不盲目INCR回加）")
    void stockRejectSoldOutPinsZero() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new StockDeductRejectedException(1L));
        // 事务外分类重读：allocated >= total → 售罄（P2b B簇换锚：ic 已冻结）
        when(stockBucketRouter.soldOutInDb(1L)).thenReturn(true);
        when(stockBucketRouter.bucketRowFull(anyLong(), anyInt(), anyLong())).thenReturn(true);  // L38：行确实用满
        when(stockBucketRouter.noSellableRowInDb(1L)).thenReturn(true);                          // L38：DB 侧无可卖行
        when(templateRepository.findById(anyLong()))
                .thenReturn(Optional.of(template(1)))                       // ① 入口读
                .thenReturn(Optional.of(tplWithStatus(100L,                 // ② 分类重读
                        ApproveStatus.APPROVED, PublishStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_OUT);
        verify(stockGateway).cancel(any(), anyInt(), anyLong(), eq(CancelCause.SOLD_OUT));
        verify(stockGateway, never()).cancel(any(), anyInt(), anyLong(), eq(CancelCause.TRANSIENT));
    }

    @Test
    @DisplayName("DB拒绝·新快照=下线 → cancel(OFFLINE)钉零 + 2002")
    void stockRejectOfflinePinsZero() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new StockDeductRejectedException(1L));
        when(templateRepository.findById(anyLong()))
                .thenReturn(Optional.of(template(1)))
                .thenReturn(Optional.of(tplWithStatus(10L,
                        ApproveStatus.APPROVED, PublishStatus.OFFLINE)));  // 分类重读：已下线

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEMPLATE_NOT_RECEIVABLE);
        verify(stockGateway).cancel(any(), anyInt(), anyLong(), eq(CancelCause.OFFLINE));
    }

    @Test
    @DisplayName("DB拒绝·新快照仍可领（瞬时竞态）→ cancel(TRANSIENT)回加 + 5001可重试")
    void stockRejectTransientReverts() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new StockDeductRejectedException(1L));
        when(templateRepository.findById(anyLong()))
                .thenReturn(Optional.of(template(1)))
                .thenReturn(Optional.of(tplWithStatus(10L,                 // 未售罄、未下线
                        ApproveStatus.APPROVED, PublishStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEGRADED);
        verify(stockGateway).cancel(any(), anyInt(), anyLong(), eq(CancelCause.TRANSIENT));
    }

    @Test
    @DisplayName("事务RuntimeException → 探针未命中 → cancel(TRANSIENT)：不还=库存泄漏（少发）")
    void runtimeExceptionCancelsTransient() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new IllegalStateException("db down"));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.empty());   // 探针：事务确实没提交

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .isInstanceOf(IllegalStateException.class);
        verify(stockGateway).cancel(any(), anyInt(), anyLong(), eq(CancelCause.TRANSIENT));
    }

    // ---------- P0 热修回归（2026-09-12，ADR-0009 §6） ----------

    @Test
    @DisplayName("P0①回归·confirm 抛 Redis 异常：绝不 cancel（不抹已提交账），返回成功")
    void confirmFailureMustNotRollbackCommittedTx() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any())).thenReturn(coupon(1L, 1));
        doThrow(new IllegalStateException("redis timeout before exec"))
                .when(stockGateway).confirm(any(), anyInt(), anyLong());   // confirm 未抵达 Redis

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isFalse();                        // DB 已提交：用户必须拿到成功
        verify(stockGateway, never()).cancel(any(), anyInt(), anyLong(), any());          // Bug ①核心：绝不回滚
    }

    @Test
    @DisplayName("P0②回归·不确定提交(commit断连)但探针命中：记账确认+返回成功，绝不 cancel")
    void uncertainCommitLandedReturnsSuccess() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new IllegalStateException("connection reset on commit"));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));   // MySQL 其实已落盘

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();                         // 按幂等命中返回成功
        verify(stockGateway, never()).cancel(any(), anyInt(), anyLong(), any());          // Bug ②核心：不盲补偿
        verify(stockGateway).confirm(any(), anyInt(), anyLong());                         // 记账确认（尽力而为）
    }

    @Test
    @DisplayName("P0②回归·不确定提交且探针自身失败：不 cancel（令牌交180s清扫），原异常上抛")
    void uncertainCommitProbeFailsLeavesToken() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new IllegalStateException("connection reset"));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenThrow(new RuntimeException("db also down"));   // 断连风暴同时打断探针

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .isInstanceOf(IllegalStateException.class);          // 原异常不被吞
        verify(stockGateway, never()).cancel(any(), anyInt(), anyLong(), any());          // 结果未知→不动，交对账
    }

    @Test
    @DisplayName("二次故障防线·cancel 自身抛异常：吞掉保业务语义（令牌交清扫），不误报 500 之外的错")
    void cancelSecondaryFailureDoesNotMaskBusinessResult() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), anyInt(), anyLong(), any()))
                .thenThrow(new DuplicateKeyException("uk_idem"));
        when(stockGateway.cancel(any(), anyInt(), anyLong(), eq(CancelCause.TRANSIENT)))
                .thenThrow(new IllegalStateException("redis also down"));   // 补偿时 Redis 也挂
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();   // 幂等命中的业务结果不被二次故障吞掉
    }
}
