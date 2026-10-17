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
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
import com.newcoupon.infrastructure.persistence.po.CouponShareDetailPO;
import com.newcoupon.open.api.dto.CalculateRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 核销订单级上下文（L64）· 集成验收（2026-10-18）。
 *
 * <p>核心：核销不再只拿“当前这一张券 + 整单原价”复算，而是按 orderNo 找回同订单
 * 全部占用单，按与试算相同的层序/互斥/递进门槛/范围金额算法整栈复算，再取当前券份额。
 *
 * <p>反例：店铺券满100减20 先减完，平台券满140减30 在试算时因剩余 130 元不够门槛而不可用；
 * 旧核销会按整单原价 150 元判门槛放行（口径比试算松）。本类钉住“核销与试算同口径”。
 */
class OrderLevelConfirmIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private CouponShareDetailMapper shareDetailMapper;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private long createCoupon(String name, int type, long faceValue, long threshold, int layer) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest(
                        name, 3200L, type, faceValue, type == 2 ? 5000L : null,
                        threshold, 10L, 1, 1, start, end, null, null, null,
                        null, null, layer, null, null, null));
        templateAppService.publish(created.id());
        return created.id();
    }

    private record OrderSetup(long userId, long shopCouponId, long platformCouponId,
                              String shopUsageNo, String platformUsageNo) {
    }

    private OrderSetup setupTwoCoupons(long platformThreshold) {
        long shopTemplate = createCoupon("店铺满100减20", 1, 2000L, 10000L, 0);
        long platformTemplate = platformThreshold == 0
                ? createCoupon("平台立减30", 3, 3000L, 0L, 1)
                : createCoupon("平台满减", 1, 3000L, platformThreshold, 1);

        long userId = System.nanoTime();
        ReceiveResult shop = receiveAppService.receive(
                new ReceiveRequest("l64-shop-" + userId, userId, shopTemplate));
        ReceiveResult platform = receiveAppService.receive(
                new ReceiveRequest("l64-platform-" + userId, userId, platformTemplate));

        String orderNo = "ORD-L64-" + userId;
        LockResult shopLock = tradeAppService.lock(
                new LockRequest(orderNo, userId, shop.couponId()));
        LockResult platformLock = tradeAppService.lock(
                new LockRequest(orderNo, userId, platform.couponId()));
        return new OrderSetup(userId, shop.couponId(), platform.couponId(),
                shopLock.usageNo(), platformLock.usageNo());
    }

    @Test
    @DisplayName("L64-1: 店铺券折后 130 元 < 平台券门槛 140 → 平台券核销被拒（旧版会按整单 150 放行）")
    void platformConfirmRejectsWhenShopDiscountDropsBelowThreshold() {
        OrderSetup o = setupTwoCoupons(14000L);

        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(
                o.platformUsageNo(), null,
                List.of(new CalculateRequest.OrderItem("SKU1", "CAT1", 15000L, 1, "L1")))))
                .as("核销按订单级递进门槛：15000 - 2000 = 13000 < 14000")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未达使用门槛");
    }

    @Test
    @DisplayName("L64-2: 店铺券折后 130 元 ≥ 平台券门槛 130 → 两张券都可核销，优惠 2000+3000")
    void platformConfirmUsesOrderLevelStack() {
        OrderSetup o = setupTwoCoupons(13000L);
        List<CalculateRequest.OrderItem> items =
                List.of(new CalculateRequest.OrderItem("SKU1", "CAT1", 15000L, 1, "L1"));

        var platformResult = tradeAppService.confirm(
                new ConfirmRequest(o.platformUsageNo(), null, items));
        assertThat(platformResult.discountAmt()).as("平台券按折后 130 元算，满 130 减 30")
                .isEqualTo(3000L);

        var shopResult = tradeAppService.confirm(
                new ConfirmRequest(o.shopUsageNo(), null, items));
        assertThat(shopResult.discountAmt()).isEqualTo(2000L);

        List<CouponShareDetailPO> shares = shareDetailMapper.selectPositiveByUsageNo(o.platformUsageNo());
        assertThat(shares).hasSize(1);
        assertThat(shares.get(0).getOrderLineNo()).isEqualTo("L1");
        assertThat(shares.get(0).getShareTotal()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("L64-3: 订单已有一张券部分退款 → 另一张券不能再核销（售后守卫，4002）")
    void confirmRejectsAfterSiblingRefund() {
        OrderSetup o = setupTwoCoupons(0L);
        List<CalculateRequest.OrderItem> items =
                List.of(new CalculateRequest.OrderItem("SKU1", "CAT1", 15000L, 2, "L1"));

        tradeAppService.confirm(new ConfirmRequest(o.shopUsageNo(), null, items));
        tradeAppService.refund(new RefundRequest(
                o.shopUsageNo(), "RF-L64-1", List.of(new RefundItem("L1", 1))));

        assertThatThrownBy(() -> tradeAppService.confirm(
                new ConfirmRequest(o.platformUsageNo(), null, items)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已进入售后");
    }
}
