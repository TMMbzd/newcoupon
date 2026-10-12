package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmResult;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
import com.newcoupon.open.api.dto.CalculateRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 核销由平台算钱 + 出资拆分 · 集成验收（第 2 环核心新增）。
 *
 * <p>验证点：
 * <ol>
 *   <li>带 items 的核销 → 平台计算优惠金额（与试算一致），coupon_share_detail 落表</li>
 *   <li>老式核销（只传 discountAmt）→ 沿用调用方金额，不写分账</li>
 * </ol>
 */
class ConfirmByPlatformIntegrationTest extends ShardedIntegrationTestSupport {

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

    /** 建模板→发布→领券→锁券，返回锁券结果（含 usageNo） */
    private LockedCoupon setupLockedCoupon(String suffix) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);

        // 建"满100减20"满减券模板（budget_owner 默认 PLATFORM）
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest(
                        "核销测试-" + suffix, 3001L, 1, 2000L, null, 10000L,
                        10L, 1, 1, start, end, null, null, null));
        templateAppService.publish(created.id());

        // 领券
        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("cbp-" + suffix, userId, created.id()));

        // 锁券
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-" + suffix, userId, rcv.couponId()));

        return new LockedCoupon(lock.usageNo(), userId, rcv.couponId());
    }

    private record LockedCoupon(String usageNo, long userId, long couponId) {}

    @Test
    @DisplayName("T1: 带 items 核销 → 平台算出优惠 2000 + coupon_share_detail 落表（总额=2000）")
    void t1_confirmWithItems() {
        LockedCoupon lc = setupLockedCoupon("T1");

        // 订单 150 元（满 100 门槛 → 优惠 20 元）
        List<CalculateRequest.OrderItem> items = List.of(
                new CalculateRequest.OrderItem("SKU1", "CAT1", 15000L, 1));

        ConfirmResult result = tradeAppService.confirm(
                new ConfirmRequest(lc.usageNo(), null, items));

        assertThat(result.discountAmt())
                .as("平台计算：满 100 减 20，订单 150 → 优惠 2000 分").isEqualTo(2000L);
        assertThat(result.couponStatus()).isEqualTo("已核销");
        assertThat(result.duplicated()).isFalse();

        // 分账明细落表（budget_owner=PLATFORM → 平台全出）
        long shareTotal = shareDetailMapper.sumShareByUsageNo(lc.usageNo());
        assertThat(shareTotal).as("分账总额 = 优惠总额").isEqualTo(2000L);
    }

    @Test
    @DisplayName("T2: 老式核销（只传 discountAmt）→ 沿用调用方金额，不写分账")
    void t2_confirmOldStyle() {
        LockedCoupon lc = setupLockedCoupon("T2");

        ConfirmResult result = tradeAppService.confirm(
                new ConfirmRequest(lc.usageNo(), 2000L));  // 老式 2 参构造器

        assertThat(result.discountAmt()).isEqualTo(2000L);
        assertThat(result.couponStatus()).isEqualTo("已核销");

        // 老式核销不写分账（没有 items，平台没有算钱，无从拆分）
        long shareTotal = shareDetailMapper.sumShareByUsageNo(lc.usageNo());
        assertThat(shareTotal).as("老式核销无分账记录").isEqualTo(0L);
    }

    @Test
    @DisplayName("T3: 带 items 核销幂等重放 → 第二次返回 duplicated=true + 相同金额")
    void t3_confirmIdempotent() {
        LockedCoupon lc = setupLockedCoupon("T3");

        List<CalculateRequest.OrderItem> items = List.of(
                new CalculateRequest.OrderItem("SKU1", "CAT1", 15000L, 1));

        // 第一次核销
        ConfirmResult first = tradeAppService.confirm(
                new ConfirmRequest(lc.usageNo(), null, items));
        assertThat(first.duplicated()).isFalse();

        // 重放（支付回调重复推送）
        ConfirmResult replay = tradeAppService.confirm(
                new ConfirmRequest(lc.usageNo(), null, items));
        assertThat(replay.duplicated()).as("重放标记 duplicated").isTrue();
        assertThat(replay.discountAmt()).as("重放返回相同金额").isEqualTo(first.discountAmt());
    }
}
