package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.RefundItem;
import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundOrderMapper;
import com.newcoupon.open.api.dto.CalculateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 环 3 · 租户退款政策验收。
 *
 * <p>验证：
 * <ol>
 *   <li>两个租户可配不同的最多退款次数；超限返回 4004；</li>
 *   <li>整单退是否退券可按租户关闭；关闭后券保持已核销；</li>
 *   <li>部分退款始终不退券（与租户配置无关）。</li>
 * </ol>
 */
class RefundPolicyIntegrationTest extends ShardedIntegrationTestSupport {

    private static final String APP_LIMIT = "t-refund-limit";
    private static final String APP_MORE = "t-refund-more";
    private static final String APP_NO_RETURN = "t-refund-no-return";
    private static final String APP_RETURN_ONCE = "t-refund-return-once";
    private static final String APP_DEFAULT = "t-refund-default";

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private OpenAppService openAppService;
    @Autowired private TenantConfigService tenantConfigService;
    @Autowired private UserCouponRepository userCouponRepository;
    @Autowired private CouponRefundOrderMapper refundOrderMapper;

    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @org.junit.jupiter.api.BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        openAppService.create(new CreateAppRequest(APP_LIMIT, "退款上限1", null, null, null));
        openAppService.create(new CreateAppRequest(APP_MORE, "退款上限5", null, null, null));
        openAppService.create(new CreateAppRequest(APP_NO_RETURN, "整单不退券", null, null, null));
        openAppService.create(new CreateAppRequest(APP_RETURN_ONCE, "一券限退一次", null, null, null));
        openAppService.create(new CreateAppRequest(APP_DEFAULT, "默认政策", null, null, null));

        tenantConfigService.put(APP_LIMIT, Map.of(
                TenantConfigKey.TRADE_REFUND_MAX_TIMES.configKey(), "1",
                TenantConfigKey.TRADE_REFUND_MAX_RETURN_TIMES.configKey(), "3",
                TenantConfigKey.TRADE_REFUND_FULL_RETURN_COUPON.configKey(), "true"), "test");
        tenantConfigService.put(APP_MORE, Map.of(
                TenantConfigKey.TRADE_REFUND_MAX_TIMES.configKey(), "5"), "test");
        tenantConfigService.put(APP_NO_RETURN, Map.of(
                TenantConfigKey.TRADE_REFUND_MAX_RETURN_TIMES.configKey(), "0",
                TenantConfigKey.TRADE_REFUND_FULL_RETURN_COUPON.configKey(), "false"), "test");
        tenantConfigService.put(APP_RETURN_ONCE, Map.of(
                TenantConfigKey.TRADE_REFUND_MAX_RETURN_TIMES.configKey(), "1",
                TenantConfigKey.TRADE_REFUND_FULL_RETURN_COUPON.configKey(), "true"), "test");
        tenantConfigService.invalidate(APP_LIMIT);
        tenantConfigService.invalidate(APP_MORE);
        tenantConfigService.invalidate(APP_NO_RETURN);
        tenantConfigService.invalidate(APP_RETURN_ONCE);
    }

    private record Confirmed(long couponId, String usageNo, long userId) {
    }

    /** 在指定租户上下文里建券→领→锁→核销；quantity=2 时一行 20000、优惠 2000 */
    private Confirmed confirmed(String appId, String suffix, int quantity) {
        RequestContext.set(new RequestContext(appId, "trace-" + suffix, Long.MAX_VALUE, false));
        try {
            LocalDateTime start = LocalDateTime.now().minusDays(1);
            LocalDateTime end = LocalDateTime.now().plusDays(30);
            TemplateAppService.CreatedTemplate created = templateAppService.create(
                    new TemplateCreateRequest(
                            "退款政策-" + suffix, 3500L, 1, 2000L, null, 10000L,
                            10L, 1, 1, start, end, null, null, null));
            templateAppService.publish(created.id());

            long userId = System.nanoTime();
            ReceiveResult rcv = receiveAppService.receive(
                    new ReceiveRequest("refund-policy-" + suffix, userId, created.id()));
            LockResult lock = tradeAppService.lock(
                    new LockRequest("ORD-REFUND-POLICY-" + suffix, userId, rcv.couponId()));
            tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null,
                    List.of(new CalculateRequest.OrderItem("SKU1", "CAT1", 10000L,
                            quantity, "L1", null))));
            return new Confirmed(rcv.couponId(), lock.usageNo(), userId);
        } finally {
            RequestContext.clear();
        }
    }

    private void refundAs(String appId, Confirmed c, String refundNo, RefundItem... items) {
        RequestContext.set(new RequestContext(appId, "trace-refund", Long.MAX_VALUE, false));
        try {
            tradeAppService.refund(new RefundRequest(c.usageNo(), refundNo,
                    items.length == 0 ? null : List.of(items)));
        } finally {
            RequestContext.clear();
        }
    }

    /** 内部面：没有 RequestContext；政策必须按券的 appId 解析，不能退回全站默认 */
    private void refundAsInternal(Confirmed c, String refundNo, RefundItem... items) {
        tradeAppService.refund(new RefundRequest(c.usageNo(), refundNo,
                items.length == 0 ? null : List.of(items)));
    }

    @Test
    @DisplayName("P1: 租户 A 最多退 1 次、租户 B 最多 5 次——A 第二次 4004，B 成功")
    void refundTimesArePerTenant() {
        Confirmed a = confirmed(APP_LIMIT, "P1A", 2);
        refundAs(APP_LIMIT, a, "RF-P1A-1", new RefundItem("L1", 1));
        assertThatThrownBy(() -> refundAs(APP_LIMIT, a, "RF-P1A-2", new RefundItem("L1", 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上限 1")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USAGE_STATE_CONFLICT);

        Confirmed b = confirmed(APP_MORE, "P1B", 2);
        refundAs(APP_MORE, b, "RF-P1B-1", new RefundItem("L1", 1));
        refundAs(APP_MORE, b, "RF-P1B-2", new RefundItem("L1", 1));
    }

    @Test
    @DisplayName("P2: 关闭整单退券 → 券保持已核销；默认租户仍退回可用")
    void fullRefundReturnIsPerTenant() {
        Confirmed noReturn = confirmed(APP_NO_RETURN, "P2A", 1);
        refundAs(APP_NO_RETURN, noReturn, "RF-P2A-FULL");
        assertThat(userCouponRepository.findById(noReturn.couponId()).orElseThrow().getStatus())
                .as("租户关闭整单退券").isEqualTo(CouponStatus.USED);
        assertThat(refundOrderMapper.selectByRefundNo("RF-P2A-FULL").getCouponReturned())
                .isZero();

        Confirmed defaultReturn = confirmed(APP_DEFAULT, "P2B", 1);
        refundAs(APP_DEFAULT, defaultReturn, "RF-P2B-FULL");
        assertThat(userCouponRepository.findById(defaultReturn.couponId()).orElseThrow().getStatus())
                .as("默认政策整单退有效期内退回可用").isEqualTo(CouponStatus.USABLE);
        assertThat(refundOrderMapper.selectByRefundNo("RF-P2B-FULL").getCouponReturned())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("P5: 一券限退 1 次——第二次整单退不再退券")
    void maxReturnTimesIsCumulativePerCoupon() {
        Confirmed c = confirmed(APP_RETURN_ONCE, "P5-1", 1);
        refundAs(APP_RETURN_ONCE, c, "RF-P5-1");
        assertThat(userCouponRepository.findById(c.couponId()).orElseThrow().getStatus())
                .as("第一次整单退，券回可用").isEqualTo(CouponStatus.USABLE);

        RequestContext.set(new RequestContext(APP_RETURN_ONCE, "trace-P5-2", Long.MAX_VALUE, false));
        try {
            LockResult lock2 = tradeAppService.lock(
                    new LockRequest("ORD-REFUND-POLICY-P5-2", c.userId(), c.couponId()));
            tradeAppService.confirm(new ConfirmRequest(lock2.usageNo(), null,
                    List.of(new CalculateRequest.OrderItem("SKU1", "CAT1", 10000L, 1, "L1", null))));
            tradeAppService.refund(new RefundRequest(lock2.usageNo(), "RF-P5-2", null));
        } finally {
            RequestContext.clear();
        }

        assertThat(userCouponRepository.findById(c.couponId()).orElseThrow().getStatus())
                .as("退券次数已达上限，券保持已核销").isEqualTo(CouponStatus.USED);
        assertThat(refundOrderMapper.selectByRefundNo("RF-P5-2").getCouponReturned())
                .isZero();
    }

    @Test
    @DisplayName("P4: 内部面无 RequestContext → 仍按券的租户政策（不是全站默认）")
    void internalFaceUsesCouponTenantPolicy() {
        Confirmed c = confirmed(APP_LIMIT, "P4", 2);
        refundAsInternal(c, "RF-P4-1", new RefundItem("L1", 1));

        assertThatThrownBy(() -> refundAsInternal(c, "RF-P4-2", new RefundItem("L1", 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上限 1");
    }

    @Test
    @DisplayName("P3: 部分退款始终不退券（默认租户）")
    void partialRefundNeverReturnsCoupon() {
        Confirmed c = confirmed(APP_DEFAULT, "P3", 2);
        refundAs(APP_DEFAULT, c, "RF-P3-PART", new RefundItem("L1", 1));

        assertThat(userCouponRepository.findById(c.couponId()).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USED);
        assertThat(refundOrderMapper.selectByRefundNo("RF-P3-PART").getCouponReturned())
                .isZero();
    }
}
