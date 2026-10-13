package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.service.DailyBillService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.infrastructure.persistence.po.CouponDailyBillPO;
import com.newcoupon.open.api.dto.CalculateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * T+1 日账单 · 集成验收（Phase 1 算钱域收官项）。
 *
 * <p>验证点：
 * <ol>
 *   <li>当日核销的分账按模板聚合成账单行（金额/笔数正确）</li>
 *   <li>退款负行进账单：同日退款净额归零、refund_count 计冲回</li>
 *   <li>重跑幂等：upsert 覆盖，不产生重复行/重复金额</li>
 * </ol>
 *
 * <p>隔离性说明：同容器其他测试的分账行也可能落在"今天"——但账单按 template_id 分行，
 * 本测试断言只看自己新建的模板行，不受污染。
 */
class DailyBillIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private DailyBillService dailyBillService;
    @Autowired private com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper billMapper;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /** 建满100减20模板→领→锁→核销（150元订单→平台算出优惠2000），返回 (templateId, usageNo) */
    private ConfirmedUsage confirmOne(String suffix) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest(
                        "账单测试-" + suffix, 3003L, 1, 2000L, null, 10000L,
                        10L, 1, 1, start, end, null, null, null));
        templateAppService.publish(created.id());

        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("bill-" + suffix + "-" + userId, userId, created.id()));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-BILL-" + suffix, userId, rcv.couponId()));

        tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null,
                List.of(new CalculateRequest.OrderItem("SKU1", "CAT1", 15000L, 1))));
        return new ConfirmedUsage(created.id(), lock.usageNo());
    }

    private record ConfirmedUsage(long templateId, String usageNo) {}

    private CouponDailyBillPO billOf(LocalDate date, long templateId) {
        return billMapper.selectByDate(date).stream()
                .filter(b -> b.getTemplateId() == templateId)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("B1: 两个模板各核销一笔 → 账单各一行（2000/平台出/商家0）；重跑不翻倍")
    void b1_aggregateAndIdempotent() {
        ConfirmedUsage a = confirmOne("B1a");
        ConfirmedUsage b = confirmOne("B1b");
        LocalDate today = LocalDate.now();

        int lines = dailyBillService.generateBill(today);
        assertThat(lines).as("至少聚出本测试两个模板行").isGreaterThanOrEqualTo(2);

        CouponDailyBillPO billA = billOf(today, a.templateId());
        assertThat(billA.getDiscountTotal()).as("模板A净优惠2000").isEqualTo(2000L);
        assertThat(billA.getPlatformBurden()).as("平台全出").isEqualTo(2000L);
        assertThat(billA.getMerchantBurden()).isEqualTo(0L);
        assertThat(billA.getConfirmCount()).isEqualTo(1);
        assertThat(billA.getRefundCount()).isEqualTo(0);

        CouponDailyBillPO billB = billOf(today, b.templateId());
        assertThat(billB.getDiscountTotal()).isEqualTo(2000L);

        // 重跑（幂等）：金额不翻倍、行数不增
        dailyBillService.generateBill(today);
        assertThat(billOf(today, a.templateId()).getDiscountTotal())
                .as("重跑后金额仍为2000（upsert覆盖非累加）").isEqualTo(2000L);
        assertThat(billMapper.selectByDate(today).stream()
                .filter(x -> x.getTemplateId() == a.templateId()).count())
                .as("仍只有一行").isEqualTo(1L);
    }

    @Test
    @DisplayName("B2: 当日退款 → 账单净额归零，冲回行计数")
    void b2_refundNetsOut() {
        ConfirmedUsage c = confirmOne("B2");
        LocalDate today = LocalDate.now();
        dailyBillService.generateBill(today);
        assertThat(billOf(today, c.templateId()).getDiscountTotal()).isEqualTo(2000L);

        // 退款冲销：分账表写镜像负行
        tradeAppService.refund(new RefundRequest(c.usageNo()));

        // 重新出账：同日退款 → 净额 0
        dailyBillService.generateBill(today);
        CouponDailyBillPO bill = billOf(today, c.templateId());
        assertThat(bill.getDiscountTotal()).as("退款后净优惠归零").isEqualTo(0L);
        assertThat(bill.getPlatformBurden()).isEqualTo(0L);
        assertThat(bill.getRefundCount()).as("计一笔冲回行").isEqualTo(1);
        assertThat(bill.getConfirmCount()).as("原核销行仍在账单上可见").isEqualTo(1);
    }
}
