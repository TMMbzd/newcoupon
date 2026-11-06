package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.RefundItem;
import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.dto.TradeDtos.ReleaseRequest;
import com.newcoupon.application.service.CalculateAppService;
import com.newcoupon.application.service.PricingSnapshotService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.application.service.TradeTxService;
import com.newcoupon.application.service.discount.DiscountCalculatorService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.open.api.dto.CalculateResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;

/**
 * L75（TOCTOU）· 集成验收（2026-11-06，阶段 6 收官）。
 *
 * <p><b>要防的事故</b>：编排层在事务外校验完“快照券集合=订单占用单集合”和“订单没有售后”，
 * 到事务层真正写核销之间还有一段窗口。窗口里同一订单的兄弟券可能被释放/退款：
 * 旧实现会按已经失效的券集合把当前券核销掉，导致“核销金额基于一张已不存在的券”。
 *
 * <p><b>怎么把窗口做成确定性的</b>：用 {@link SpyBean} 包住窗口前最后一个校验点
 * （快照路径=prepareConfirm、L64 复算路径=calculateForOrderConfirm），在真实方法返回后、
 * 事务开始前注入 sibling 释放/退款。这比两线程交错更可控——红/绿都只取决于被测代码，
 * 不取决于调度运气。
 *
 * <p>修复后的判据（事务内按 id 升序锁住同订单全部占用单后重校验）：
 * 兄弟券窗口内释放 → 券集合不再等于核销时使用的集合 → 拒绝；
 * 兄弟券窗口内退款 → 订单已进入售后 → 拒绝；目标单本身状态不变，可安全重试。
 * 幂等重放（usage 已 CONFIRMED）不受新校验影响，仍返回 duplicated=true。
 */
@TestPropertySource(properties = {
        "coupon.trade.pricing.enabled=true",
        "coupon.trade.settle-party.enabled=true"
})
class L75ConfirmToctouIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private CalculateAppService calculateAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private TradeTxService tradeTxService;
    @Autowired private UserCouponRepository userCouponRepository;

    /** 仓储 spy：C2 用它把核销事务挂在 casConfirm 之前，证明订单行锁被持有到提交 */
    @SpyBean private CouponUsageRepository couponUsageRepository;

    /** 快照路径的窗口注入点：prepareConfirm 真实校验通过之后、事务开始之前 */
    @SpyBean private PricingSnapshotService pricingSnapshotService;

    /** L64 旧路径的窗口注入点：订单级复算完成之后、事务开始之前 */
    @SpyBean private DiscountCalculatorService discountCalculatorService;

    private final AtomicBoolean windowArmed = new AtomicBoolean(false);
    private final AtomicBoolean legacyWindowArmed = new AtomicBoolean(false);
    private volatile Runnable windowAction = () -> { };
    private volatile Runnable legacyWindowAction = () -> { };

    /** C2 两线程取证：A 到达 casConfirm 前挂起（此时订单行锁已持有），B 必须被阻塞 */
    private final AtomicBoolean confirmBlockArmed = new AtomicBoolean(false);
    private final CountDownLatch confirmBlocked = new CountDownLatch(1);
    private final CountDownLatch confirmResume = new CountDownLatch(1);

    @BeforeEach
    void installWindowHooks() {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (windowArmed.compareAndSet(true, false)) {
                windowAction.run();
            }
            return result;
        }).when(pricingSnapshotService).prepareConfirm(anyString(), any(CouponUsage.class),
                any(), anyBoolean(), any(LocalDateTime.class));

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (legacyWindowArmed.compareAndSet(true, false)) {
                legacyWindowAction.run();
            }
            return result;
        }).when(discountCalculatorService).calculateForOrderConfirm(anyLong(), anyList(), anyList());

        // C2：核销事务在“锁行之后、CAS 之前”挂起——此间订单行锁必须一直持有，
        // 并发 sibling 释放/退款会被数据库阻塞。摘掉 FOR UPDATE 时这里不再持有行锁，
        // 并发操作会立即完成，用例即红（变异自检）。
        doAnswer(invocation -> {
            if (confirmBlockArmed.compareAndSet(true, false)) {
                confirmBlocked.countDown();
                if (!confirmResume.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("C2 测试闩超时：执行线程未被放行");
                }
            }
            return invocation.callRealMethod();
        }).when(couponUsageRepository).casConfirm(anyString(), anyLong(), anyLong(),
                any(LocalDateTime.class));
    }

    private record Setup(long userId, long shopCouponId, long platformCouponId) {
    }

    private record Fixture(Setup setup, String orderNo, String pricingId,
                           String shopUsageNo, String platformUsageNo) {
    }

    private Setup issueTwoCoupons(String suffix) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate shop = templateAppService.create(
                new TemplateCreateRequest("L75-店铺券-" + suffix, 9400L, 1, 500L, null, 5000L,
                        100L, 2, 1, start, end, null, 0L, 0, null, null, 0, null, 1, null, null));
        templateAppService.publish(shop.id());
        TemplateAppService.CreatedTemplate platform = templateAppService.create(
                new TemplateCreateRequest("L75-平台券-" + suffix, 9401L, 1, 300L, null, 5000L,
                        100L, 1, 1, start, end, null, 0L, 0, null, null, 1, null, 1, null, null));
        templateAppService.publish(platform.id());

        long userId = System.nanoTime();
        ReceiveResult shopCoupon = receiveAppService.receive(
                new ReceiveRequest("l75-shop-" + suffix, userId, shop.id()));
        ReceiveResult platformCoupon = receiveAppService.receive(
                new ReceiveRequest("l75-platform-" + suffix, userId, platform.id()));
        return new Setup(userId, shopCoupon.couponId(), platformCoupon.couponId());
    }

    private static List<CalculateRequest.OrderItem> items() {
        return items(1);
    }

    private static List<CalculateRequest.OrderItem> items(int quantity) {
        return List.of(new CalculateRequest.OrderItem("SKU1", "CAT1", 10000L, quantity, "L1", "A"));
    }

    /** 单张券：建模板→发布→领券（归属跟随当前 RequestContext 的租户） */
    private long issueCoupon(String suffix, long userId) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate t = templateAppService.create(
                new TemplateCreateRequest("L75-跨租户券-" + suffix, 9500L, 1, 500L, null, 5000L,
                        100L, 1, 1, start, end, null, 0L, 0, null, null, 0, null, 1, null, null));
        templateAppService.publish(t.id());
        return receiveAppService.receive(new ReceiveRequest("l75-xt-" + suffix, userId, t.id()))
                .couponId();
    }

    /** 两张券同单、带价格快照锁好（快照 APPLIED={店铺券, 平台券}） */
    private Fixture lockBothWithSnapshot(String suffix) {
        return lockBothWithSnapshot(suffix, 1);
    }

    private Fixture lockBothWithSnapshot(String suffix, int quantity) {
        Setup s = issueTwoCoupons(suffix);
        String orderNo = "ORD-L75-" + suffix;
        CalculateResponse calc = calculateAppService.calculate(
                new CalculateRequest(s.userId(), items(quantity),
                        List.of(s.shopCouponId(), s.platformCouponId()), orderNo));
        assertThat(calc.pricingId()).isNotBlank();
        LockResult shopLock = tradeAppService.lock(new LockRequest(orderNo, s.userId(),
                s.shopCouponId(), calc.pricingId()));
        LockResult platformLock = tradeAppService.lock(new LockRequest(orderNo, s.userId(),
                s.platformCouponId(), calc.pricingId()));
        return new Fixture(s, orderNo, calc.pricingId(), shopLock.usageNo(), platformLock.usageNo());
    }

    /** 两张券同单、旧式路径锁好（无价格快照） */
    private Fixture lockBothLegacy(String suffix) {
        Setup s = issueTwoCoupons(suffix);
        String orderNo = "ORD-L75L-" + suffix;
        LockResult shopLock = tradeAppService.lock(
                new LockRequest(orderNo, s.userId(), s.shopCouponId()));
        LockResult platformLock = tradeAppService.lock(
                new LockRequest(orderNo, s.userId(), s.platformCouponId()));
        return new Fixture(s, orderNo, null, shopLock.usageNo(), platformLock.usageNo());
    }

    private UsageStatus usageStatus(String usageNo) {
        return couponUsageRepository.findByUsageNo(usageNo).orElseThrow().getStatus();
    }

    private CouponStatus couponStatus(long couponId) {
        return userCouponRepository.findById(couponId).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("T1: 快照校验通过后窗口内 sibling 被释放 → confirm 拒绝（4007 券集合）且钉住异常单，超时不回可用")
    void siblingReleaseInWindowRejectsSnapshotConfirm() {
        Fixture f = lockBothWithSnapshot("REL");
        windowAction = () -> tradeAppService.release(
                new ReleaseRequest(f.platformUsageNo(), "窗口内释放"));
        windowArmed.set(true);

        assertThatThrownBy(() -> tradeAppService.confirm(
                new ConfirmRequest(f.shopUsageNo(), null, null, f.pricingId())))
                .as("窗口内兄弟券被释放后，事务内重校验必须拒绝按失效快照核销")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("券集合")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRICING_SNAPSHOT_INVALID);

        assertThat(windowArmed).as("窗口注入钩子确实触发过").isFalse();
        assertThat(usageStatus(f.platformUsageNo())).isEqualTo(UsageStatus.RELEASED);
        // L75 审查 C1：4007 必须把目标单钉在 CONFIRM_EXCEPTION。否则占用单停在
        // OCCUPYING，L5 超时任务到点会把券放回 USABLE——用户已按此券优惠付过钱，
        // 券又能再用=双重优惠。钉住后 timeoutClose 必须 no-op。
        assertThat(usageStatus(f.shopUsageNo())).isEqualTo(UsageStatus.CONFIRM_EXCEPTION);
        assertThat(tradeTxService.timeoutClose(f.shopUsageNo(),
                LocalDateTime.now().plusMinutes(31)))
                .as("异常单不得被锁超时任务收场").isFalse();
        assertThat(usageStatus(f.shopUsageNo())).isEqualTo(UsageStatus.CONFIRM_EXCEPTION);
        assertThat(couponStatus(f.setup().shopCouponId())).isEqualTo(CouponStatus.LOCKED);
    }

    @Test
    @DisplayName("T2: 快照校验通过后窗口内 sibling 被退款 → confirm 拒绝（4002 已进入售后），目标单保持占用中")
    void siblingRefundInWindowRejectsSnapshotConfirm() {
        Fixture f = lockBothWithSnapshot("RF");
        // 兄弟券先正常核销（制造“可退款”的前置），再在目标券核销的窗口里退款
        assertThat(tradeAppService.confirm(
                new ConfirmRequest(f.platformUsageNo(), null, null, f.pricingId())).duplicated())
                .isFalse();
        windowAction = () -> tradeAppService.refund(
                new RefundRequest(f.platformUsageNo(), "RF-L75-WINDOW", null));
        windowArmed.set(true);

        assertThatThrownBy(() -> tradeAppService.confirm(
                new ConfirmRequest(f.shopUsageNo(), null, null, f.pricingId())))
                .as("窗口内兄弟券退款后，事务内售后态重校验必须拒绝")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已进入售后")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        assertThat(windowArmed).as("窗口注入钩子确实触发过").isFalse();
        assertThat(usageStatus(f.platformUsageNo())).isEqualTo(UsageStatus.REFUNDED);
        assertThat(usageStatus(f.shopUsageNo())).isEqualTo(UsageStatus.OCCUPYING);
        assertThat(couponStatus(f.setup().shopCouponId())).isEqualTo(CouponStatus.LOCKED);
    }

    @Test
    @DisplayName("T3: 幂等重放不拿新校验的拒绝——已核销券在 sibling 退款后重放，仍返回 duplicated=true")
    void replayAfterSiblingRefundStaysDuplicated() {
        Fixture f = lockBothWithSnapshot("REP");
        var shopFirst = tradeAppService.confirm(
                new ConfirmRequest(f.shopUsageNo(), null, null, f.pricingId()));
        var platformFirst = tradeAppService.confirm(
                new ConfirmRequest(f.platformUsageNo(), null, null, f.pricingId()));
        // 兄弟券（店铺券）退款后，重放平台券核销：已成功的核销不能被售后态误伤
        tradeAppService.refund(new RefundRequest(f.shopUsageNo(), "RF-L75-REPLAY", null));

        var replay = tradeAppService.confirm(
                new ConfirmRequest(f.platformUsageNo(), null, null, f.pricingId()));

        assertThat(replay.duplicated()).isTrue();
        assertThat(replay.discountAmt()).isEqualTo(platformFirst.discountAmt());
        assertThat(shopFirst.duplicated()).isFalse();
    }

    @Test
    @DisplayName("T4: 正常顺序核销两张券不被新锁误伤（已核销兄弟券算生效集合）")
    void sequentialConfirmOfBothCouponsStillWorks() {
        Fixture f = lockBothWithSnapshot("SEQ");

        var platform = tradeAppService.confirm(
                new ConfirmRequest(f.platformUsageNo(), null, null, f.pricingId()));
        var shop = tradeAppService.confirm(
                new ConfirmRequest(f.shopUsageNo(), null, null, f.pricingId()));

        assertThat(platform.duplicated()).isFalse();
        assertThat(shop.duplicated()).isFalse();
        assertThat(usageStatus(f.platformUsageNo())).isEqualTo(UsageStatus.CONFIRMED);
        assertThat(usageStatus(f.shopUsageNo())).isEqualTo(UsageStatus.CONFIRMED);
    }

    @Test
    @DisplayName("T5: 旧式金额核销（无 items/无快照）同享售后态重校验——兄弟退款后拒绝 4002")
    void legacyAmountConfirmRejectsAfterSiblingRefund() {
        Fixture f = lockBothLegacy("LEG");
        tradeAppService.confirm(new ConfirmRequest(f.platformUsageNo(), 300L));
        tradeAppService.refund(new RefundRequest(f.platformUsageNo(), "RF-L75-LEGACY", null));

        assertThatThrownBy(() -> tradeAppService.confirm(
                new ConfirmRequest(f.shopUsageNo(), 500L)))
                .as("旧式金额核销也必须先看订单售后态")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已进入售后")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
        assertThat(usageStatus(f.shopUsageNo())).isEqualTo(UsageStatus.OCCUPYING);
    }

    @Test
    @DisplayName("T6: L64 复算完成后的窗口内 sibling 被释放 → confirm 拒绝（4002 占用单集合变化）")
    void siblingReleaseInWindowRejectsLegacyItemsConfirm() {
        Fixture f = lockBothLegacy("L64");
        legacyWindowAction = () -> tradeAppService.release(
                new ReleaseRequest(f.platformUsageNo(), "窗口内释放"));
        legacyWindowArmed.set(true);

        assertThatThrownBy(() -> tradeAppService.confirm(
                new ConfirmRequest(f.shopUsageNo(), null, items())))
                .as("复算用到的券集合在窗口内变了，事务内必须拒绝")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("占用单集合")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        assertThat(legacyWindowArmed).as("窗口注入钩子确实触发过").isFalse();
        assertThat(usageStatus(f.platformUsageNo())).isEqualTo(UsageStatus.RELEASED);
        assertThat(usageStatus(f.shopUsageNo())).isEqualTo(UsageStatus.OCCUPYING);
    }

    private record Res(Object result, String code) {
    }

    private Res call(java.util.function.Supplier<Object> action) {
        try {
            return new Res(action.get(), "OK");
        } catch (BizException e) {
            return new Res(null, String.valueOf(e.getErrorCode().getCode()));
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("T7: 两线程证明事务内订单行锁真实存在——A 核销持锁时 B 释放 sibling 必须阻塞")
    void confirmHoldsOrderRowLocksUntilCommit() throws Exception {
        Fixture f = lockBothLegacy("LOCK");
        confirmBlockArmed.set(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Res> confirm = pool.submit(() -> call(() ->
                    tradeAppService.confirm(new ConfirmRequest(f.shopUsageNo(), 500L))));
            assertThat(confirmBlocked.await(30, TimeUnit.SECONDS))
                    .as("A 应到达“锁行之后、CAS 之前”的挂起点").isTrue();

            Future<Res> release = pool.submit(() -> call(() ->
                    tradeAppService.release(new ReleaseRequest(f.platformUsageNo(), "并发释放"))));
            Thread.sleep(500);
            assertThat(release.isDone())
                    .as("B 必须被 A 持有的订单占用单行锁阻塞"
                            + "（摘掉 FOR UPDATE 时这里会立即完成——C2 变异自检）")
                    .isFalse();

            confirmResume.countDown();
            assertThat(confirm.get(30, TimeUnit.SECONDS).code()).isEqualTo("OK");
            assertThat(release.get(30, TimeUnit.SECONDS).code()).isEqualTo("OK");
            assertThat(usageStatus(f.shopUsageNo())).isEqualTo(UsageStatus.CONFIRMED);
            assertThat(usageStatus(f.platformUsageNo())).isEqualTo(UsageStatus.RELEASED);
        } finally {
            confirmResume.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("T8: 纯旧式金额路径的事务层幂等——目标已核销+兄弟已退款，重放 duplicated 先于售后")
    void legacyReplayHitsTransactionDuplicateBeforeAfterSales() {
        Fixture f = lockBothLegacy("IDEM");
        assertThat(tradeAppService.confirm(
                new ConfirmRequest(f.shopUsageNo(), 500L)).duplicated()).isFalse();
        assertThat(tradeAppService.confirm(
                new ConfirmRequest(f.platformUsageNo(), 300L)).duplicated()).isFalse();
        tradeAppService.refund(new RefundRequest(f.shopUsageNo(), "RF-L75-LEGACY-REPLAY", null));

        // 纯旧式金额路径不走编排层的计价分支，会真正进入事务层——证明 duplicated
        // 判断位于售后/券集合校验之前：已成功的核销不能被兄弟退款误伤成 4002。
        var replay = tradeAppService.confirm(new ConfirmRequest(f.platformUsageNo(), 300L));

        assertThat(replay.duplicated()).isTrue();
        assertThat(replay.discountAmt()).isEqualTo(300L);
        assertThat(usageStatus(f.platformUsageNo())).isEqualTo(UsageStatus.CONFIRMED);
    }

}
