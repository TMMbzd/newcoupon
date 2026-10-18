package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.service.CalculateAppService;
import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.application.service.ConfirmExceptionService;
import com.newcoupon.application.service.PricingSnapshotService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.application.service.TradeTxService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponConfirmExceptionMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
import com.newcoupon.infrastructure.persistence.po.CouponConfirmExceptionPO;
import com.newcoupon.infrastructure.persistence.po.CouponShareDetailPO;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.open.api.dto.CalculateResponse;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 环 2 价格快照 · 集成验收（2026-10-18）。
 *
 * <p>验证：
 * <ol>
 *   <li>同摘要复用同一 pricingId；无 orderNo 不生成快照；</li>
 *   <li>试算后改模板，带 pricingId 核销金额/分摊不变；不带 items 也能核销；</li>
 *   <li>过期/少锁券/行不一致/跨租户 fail-closed（4007），并进入 CONFIRM_EXCEPTION；</li>
 *   <li>超时任务不释放异常单；人工 RELEASE/CONFIRM 能解除；</li>
 *   <li>锁券绑定快照后，核销漏传 pricingId 也按绑定解析；</li>
 *   <li>旧调用方（无 pricingId 带 items）行为不变。</li>
 * </ol>
 */
@TestPropertySource(properties = {
        "coupon.trade.pricing.enabled=true",
        "coupon.trade.settle-party.enabled=true"
})
class PricingSnapshotIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private CalculateAppService calculateAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private TradeTxService tradeTxService;
    @Autowired private PricingSnapshotService pricingSnapshotService;
    @Autowired private com.newcoupon.application.service.PricingSnapshotCleanupService cleanupService;
    @Autowired private ConfirmExceptionService confirmExceptionService;
    @Autowired private CachedTemplateReader cachedTemplateReader;
    @Autowired private CouponUsageRepository couponUsageRepository;
    @Autowired private UserCouponRepository userCouponRepository;
    @Autowired private CouponShareDetailMapper shareDetailMapper;
    @Autowired private CouponConfirmExceptionMapper confirmExceptionMapper;
    @Autowired private DataSource dataSource;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private record Setup(long templateId, long couponId, long userId) {
    }

    private record Locked(Setup setup, String orderNo, String pricingId, String usageNo) {
    }

    private TemplateCreateRequest template(String name, long faceValue, long threshold,
                                           int perUserLimit, String suffix) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        return new TemplateCreateRequest(name, 9300L, 1, faceValue, null, threshold,
                100L, perUserLimit, 1, start, end, null, 0L, 0,
                null, null, 0, null, 1, null, null);
    }

    private Setup issue(String suffix, long faceValue, long threshold, int perUserLimit) {
        TemplateAppService.CreatedTemplate created =
                templateAppService.create(template("快照券-" + suffix, faceValue, threshold,
                        perUserLimit, suffix));
        templateAppService.publish(created.id());
        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("ps-" + suffix, userId, created.id()));
        return new Setup(created.id(), rcv.couponId(), userId);
    }

    /** 平台层券：与店铺层券可叠加，用来构造“快照 APPLIED 有两张券”的场景 */
    private long issuePlatformCoupon(Setup shopSetup, String suffix) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateCreateRequest req = new TemplateCreateRequest(
                "快照平台券-" + suffix, 9301L, 1, 300L, null, 5000L,
                100L, 1, 1, start, end, null, 0L, 0,
                null, null, 1, null, 1, null, null);
        TemplateAppService.CreatedTemplate created = templateAppService.create(req);
        templateAppService.publish(created.id());
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("ps-" + suffix, shopSetup.userId(), created.id()));
        return rcv.couponId();
    }

    private static CalculateRequest.OrderItem item(String sku, long price, int qty,
                                                   String lineNo, String party) {
        return new CalculateRequest.OrderItem(sku, "CAT1", price, qty, lineNo, party);
    }

    private CalculateResponse calculate(long userId, String orderNo, List<Long> couponIds,
                                        List<CalculateRequest.OrderItem> items) {
        return calculateAppService.calculate(
                new CalculateRequest(userId, items, couponIds, orderNo));
    }

    private Locked calculateAndLock(Setup setup, String suffix,
                                    List<CalculateRequest.OrderItem> items) {
        String orderNo = "ORD-PS-" + suffix;
        CalculateResponse calc = calculate(setup.userId(), orderNo,
                List.of(setup.couponId()), items);
        assertThat(calc.pricingId()).isNotBlank();
        LockResult lock = tradeAppService.lock(new LockRequest(orderNo, setup.userId(),
                setup.couponId(), calc.pricingId()));
        return new Locked(setup, orderNo, calc.pricingId(), lock.usageNo());
    }

    private UsageStatus usageStatus(String usageNo) {
        return couponUsageRepository.findByUsageNo(usageNo).orElseThrow().getStatus();
    }

    private CouponStatus couponStatus(long couponId) {
        return userCouponRepository.findById(couponId).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("P1: 试算→改模板→带 pricingId 无 items 核销，金额不变且分账带快照ID")
    void confirmUsesFrozenSnapshotAfterTemplateChanged() {
        Setup s = issue("P1", 1000L, 5000L, 1);
        Locked locked = calculateAndLock(s, "P1", List.of(item("SKU1", 10000L, 1, "L1", "A")));

        jdbc().update("UPDATE coupon_template SET face_value = 9000 WHERE id = ?", s.templateId());
        cachedTemplateReader.invalidate(s.templateId());

        var result = tradeAppService.confirm(new ConfirmRequest(locked.usageNo(), null, null,
                locked.pricingId()));

        assertThat(result.discountAmt()).isEqualTo(1000L);
        List<CouponShareDetailPO> shares =
                shareDetailMapper.selectPositiveByUsageNo(locked.usageNo());
        assertThat(shares).hasSize(1);
        assertThat(shares.get(0).getShareTotal()).isEqualTo(1000L);
        assertThat(shares.get(0).getPricingId()).isEqualTo(locked.pricingId());
    }

    @Test
    @DisplayName("P2: 同摘要两次试算复用同一 pricingId；不同订单号生成新 ID")
    void sameDigestReusesPricingId() {
        Setup s = issue("P2", 1000L, 5000L, 1);
        List<CalculateRequest.OrderItem> items = List.of(item("SKU1", 10000L, 1, "L1", "A"));

        CalculateResponse a = calculate(s.userId(), "ORD-PS-P2-A", List.of(s.couponId()), items);
        CalculateResponse b = calculate(s.userId(), "ORD-PS-P2-A", List.of(s.couponId()), items);
        CalculateResponse c = calculate(s.userId(), "ORD-PS-P2-B", List.of(s.couponId()), items);

        assertThat(a.pricingId()).isNotBlank().isEqualTo(b.pricingId());
        assertThat(c.pricingId()).isNotBlank().isNotEqualTo(a.pricingId());
        assertThat(b.discountAmount()).isEqualTo(a.discountAmount());
    }

    @Test
    @DisplayName("P3: 无 orderNo 不生成快照、pricingId 为空")
    void noOrderNoNoSnapshot() {
        Setup s = issue("P3", 1000L, 5000L, 1);
        CalculateResponse response = calculateAppService.calculate(
                new CalculateRequest(s.userId(),
                        List.of(item("SKU1", 10000L, 1, "L1", "A")),
                        List.of(s.couponId())));

        assertThat(response.pricingId()).isNull();
        Long count = jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_pricing_snapshot WHERE user_id = ?",
                Long.class, s.userId());
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("P4: 快照过期 → 4007 + CONFIRM_EXCEPTION；超时不释放；人工 RELEASE 解除")
    void expiredSnapshotFailsClosedAndTimeoutDoesNotRelease() {
        Setup s = issue("P4", 1000L, 5000L, 1);
        Locked locked = calculateAndLock(s, "P4", List.of(item("SKU1", 10000L, 1, "L1", "A")));
        jdbc().update("UPDATE coupon_pricing_snapshot SET expires_at = ? WHERE pricing_id = ?",
                LocalDateTime.now().minusMinutes(5), locked.pricingId());

        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(
                locked.usageNo(), null, null, locked.pricingId())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRICING_SNAPSHOT_INVALID);

        assertThat(usageStatus(locked.usageNo())).isEqualTo(UsageStatus.CONFIRM_EXCEPTION);
        assertThat(couponStatus(s.couponId())).isEqualTo(CouponStatus.LOCKED);

        boolean closed = tradeTxService.timeoutClose(locked.usageNo(),
                LocalDateTime.now().plusMinutes(31));
        assertThat(closed).isFalse();
        assertThat(usageStatus(locked.usageNo())).isEqualTo(UsageStatus.CONFIRM_EXCEPTION);
        assertThat(couponStatus(s.couponId())).isEqualTo(CouponStatus.LOCKED);

        CouponConfirmExceptionPO row =
                confirmExceptionMapper.selectByUsageNo(locked.usageNo());
        assertThat(row).isNotNull();
        assertThat(row.getResolveStatus()).isEqualTo("PENDING");

        var resolved = confirmExceptionService.resolve(locked.usageNo(), "RELEASE", "订单已退款");
        assertThat(resolved.usageStatus()).isEqualTo(UsageStatus.RELEASED.getDesc());
        assertThat(usageStatus(locked.usageNo())).isEqualTo(UsageStatus.RELEASED);
        assertThat(couponStatus(s.couponId())).isEqualTo(CouponStatus.USABLE);
    }

    @Test
    @DisplayName("P5: 少锁一张券 → 4007（L70 券集合不一致）")
    void missingLockedCouponFailsClosed() {
        Setup s = issue("P5", 500L, 5000L, 2);
        long secondCoupon = issuePlatformCoupon(s, "P5-2");
        String orderNo = "ORD-PS-P5";
        List<CalculateRequest.OrderItem> items = List.of(item("SKU1", 10000L, 1, "L1", "A"));
        CalculateResponse calc = calculate(s.userId(), orderNo,
                List.of(s.couponId(), secondCoupon), items);

        LockResult lock = tradeAppService.lock(new LockRequest(orderNo, s.userId(),
                s.couponId(), calc.pricingId()));

        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(
                lock.usageNo(), null, null, calc.pricingId())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("券集合")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRICING_SNAPSHOT_INVALID);

        assertThat(usageStatus(lock.usageNo())).isEqualTo(UsageStatus.CONFIRM_EXCEPTION);
    }

    @Test
    @DisplayName("P6: 核销行与快照不一致 → 4007；快照仍有效时可人工 CONFIRM")
    void lineMismatchFailsClosedThenManualConfirm() {
        Setup s = issue("P6", 1000L, 5000L, 1);
        Locked locked = calculateAndLock(s, "P6", List.of(item("SKU1", 10000L, 1, "L1", "A")));

        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(
                locked.usageNo(), null,
                List.of(item("SKU1", 9999L, 1, "L1", "A")), locked.pricingId())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不一致")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRICING_SNAPSHOT_INVALID);
        assertThat(usageStatus(locked.usageNo())).isEqualTo(UsageStatus.CONFIRM_EXCEPTION);

        var resolved = confirmExceptionService.resolve(locked.usageNo(), "CONFIRM", "人工核对");
        assertThat(resolved.discountAmt()).isEqualTo(1000L);
        assertThat(usageStatus(locked.usageNo())).isEqualTo(UsageStatus.CONFIRMED);
        assertThat(couponStatus(s.couponId())).isEqualTo(CouponStatus.USED);
        List<CouponShareDetailPO> shares =
                shareDetailMapper.selectPositiveByUsageNo(locked.usageNo());
        assertThat(shares).hasSize(1);
        assertThat(shares.get(0).getPricingId()).isEqualTo(locked.pricingId());
    }

    @Test
    @DisplayName("P7: 锁券未带快照 → 核销临时塞 pricingId 被拒（4007）")
    void confirmCannotInjectSnapshotWhenLockHadNone() {
        Setup s = issue("P7", 1000L, 5000L, 1);
        String orderNo = "ORD-PS-P7";
        CalculateResponse calc = calculate(s.userId(), "ORD-PS-P7-CALC",
                List.of(s.couponId()), List.of(item("SKU1", 10000L, 1, "L1", "A")));
        LockResult lock = tradeAppService.lock(new LockRequest(orderNo, s.userId(),
                s.couponId()));

        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(
                lock.usageNo(), null, null, calc.pricingId())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未绑定")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRICING_SNAPSHOT_INVALID);
        assertThat(usageStatus(lock.usageNo())).isEqualTo(UsageStatus.CONFIRM_EXCEPTION);

        // 异常行不得保存“核销时临时塞进来的 ID”：没有绑定就是 null，人工不能按它核销
        CouponConfirmExceptionPO row = confirmExceptionMapper.selectByUsageNo(lock.usageNo());
        assertThat(row.getPricingId()).isNull();
        assertThatThrownBy(() -> confirmExceptionService.resolve(lock.usageNo(), "CONFIRM", "试图套用"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有价格快照");
    }

    @Test
    @DisplayName("P8: 核销漏传 pricingId 时按锁券绑定自动解析快照")
    void confirmWithoutPricingIdUsesBinding() {
        Setup s = issue("P8", 1000L, 5000L, 1);
        Locked locked = calculateAndLock(s, "P8", List.of(item("SKU1", 10000L, 1, "L1", "A")));
        jdbc().update("UPDATE coupon_template SET face_value = 9000 WHERE id = ?", s.templateId());
        cachedTemplateReader.invalidate(s.templateId());

        var result = tradeAppService.confirm(new ConfirmRequest(locked.usageNo(), 9999L));

        assertThat(result.discountAmt()).isEqualTo(1000L);
        assertThat(shareDetailMapper.selectPositiveByUsageNo(locked.usageNo()))
                .singleElement()
                .extracting(CouponShareDetailPO::getPricingId)
                .isEqualTo(locked.pricingId());
    }

    @Test
    @DisplayName("P9: 跨租户使用快照 → 4007，不泄漏快照是否存在")
    void crossTenantSnapshotRejected() {
        RequestContext.set(new RequestContext("tenant-A", "trace-A", Long.MAX_VALUE, false));
        Setup s;
        Locked locked;
        try {
            s = issue("P9", 1000L, 5000L, 1);
            locked = calculateAndLock(s, "P9", List.of(item("SKU1", 10000L, 1, "L1", "A")));
        } finally {
            RequestContext.clear();
        }

        RequestContext.set(new RequestContext("tenant-B", "trace-B", Long.MAX_VALUE, false));
        try {
            var usage = couponUsageRepository.findByUsageNo(locked.usageNo()).orElseThrow();
            assertThatThrownBy(() -> pricingSnapshotService.prepareConfirm(
                    locked.pricingId(), usage, null, false, LocalDateTime.now()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不属于当前应用")
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PRICING_SNAPSHOT_INVALID);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("P10: 重复核销幂等，分账不重复写")
    void duplicateConfirmIdempotent() {
        Setup s = issue("P10", 1000L, 5000L, 1);
        Locked locked = calculateAndLock(s, "P10", List.of(item("SKU1", 10000L, 1, "L1", "A")));

        var first = tradeAppService.confirm(new ConfirmRequest(locked.usageNo(), null, null,
                locked.pricingId()));
        var second = tradeAppService.confirm(new ConfirmRequest(locked.usageNo(), null, null,
                locked.pricingId()));

        assertThat(first.duplicated()).isFalse();
        assertThat(second.duplicated()).isTrue();
        assertThat(second.discountAmt()).isEqualTo(first.discountAmt());
        assertThat(shareDetailMapper.selectPositiveByUsageNo(locked.usageNo())).hasSize(1);
    }

    @Test
    @DisplayName("P11: 旧调用方（无 pricingId，带 items）行为不变，分账 pricing_id 为空")
    void legacyItemsConfirmStillWorks() {
        Setup s = issue("P11", 1000L, 5000L, 1);
        String orderNo = "ORD-PS-P11";
        LockResult lock = tradeAppService.lock(new LockRequest(orderNo, s.userId(), s.couponId()));

        var result = tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null,
                List.of(item("SKU1", 10000L, 1, "L1", "A"))));

        assertThat(result.discountAmt()).isEqualTo(1000L);
        assertThat(shareDetailMapper.selectPositiveByUsageNo(lock.usageNo()))
                .singleElement()
                .satisfies(share -> assertThat(share.getPricingId()).isNull());
    }

    @Test
    @DisplayName("P12: 锁券时券不在快照可用集合 → 4007")
    void lockCouponNotInSnapshotRejected() {
        Setup s = issue("P12", 1000L, 5000L, 2);
        long secondCoupon = issuePlatformCoupon(s, "P12-2");
        String orderNo = "ORD-PS-P12";
        CalculateResponse calc = calculate(s.userId(), orderNo, List.of(s.couponId()),
                List.of(item("SKU1", 10000L, 1, "L1", "A")));

        assertThatThrownBy(() -> tradeAppService.lock(new LockRequest(orderNo, s.userId(),
                secondCoupon, calc.pricingId())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRICING_SNAPSHOT_INVALID);
    }

    @Test
    @DisplayName("P14: 清理先置空 live_digest（保留审计），超过 7 天才物理删除")
    void cleanupExpiresThenDeletes() {
        Setup s = issue("P14", 1000L, 5000L, 1);
        CalculateResponse calc = calculate(s.userId(), "ORD-PS-P14",
                List.of(s.couponId()), List.of(item("SKU1", 10000L, 1, "L1", "A")));
        jdbc().update("UPDATE coupon_pricing_snapshot SET expires_at = ? WHERE pricing_id = ?",
                LocalDateTime.now().minusMinutes(1), calc.pricingId());

        cleanupService.cleanup();
        String live = jdbc().queryForObject(
                "SELECT live_digest FROM coupon_pricing_snapshot WHERE pricing_id = ?",
                String.class, calc.pricingId());
        assertThat(live).isNull();
        Long stillThere = jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_pricing_snapshot WHERE pricing_id = ?",
                Long.class, calc.pricingId());
        assertThat(stillThere).isEqualTo(1L);

        jdbc().update("UPDATE coupon_pricing_snapshot SET expires_at = ? WHERE pricing_id = ?",
                LocalDateTime.now().minusDays(8), calc.pricingId());
        cleanupService.cleanup();
        Long deleted = jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_pricing_snapshot WHERE pricing_id = ?",
                Long.class, calc.pricingId());
        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("P13: 快照 payload 冻结主体/引擎版本/占比上限")
    void payloadFreezesContext() {
        Setup s = issue("P13", 1000L, 5000L, 1);
        Locked locked = calculateAndLock(s, "P13",
                List.of(item("SKU1", 10000L, 1, "L1", "A")));

        String payload = jdbc().queryForObject(
                "SELECT payload FROM coupon_pricing_snapshot WHERE pricing_id = ?",
                String.class, locked.pricingId());
        assertThat(payload)
                .contains("\"engineVersion\":\"ring2-v1\"")
                .contains("\"settlePartyEnabled\":true")
                .contains("\"maxDiscountRatio\":80")
                .contains("\"settlePartyId\":\"A\"");
    }
}
