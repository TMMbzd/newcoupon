package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.RefundItem;
import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.service.DailyBillService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponSettlePartyDailyBillMapper;
import com.newcoupon.infrastructure.persistence.po.CouponDailyBillPO;
import com.newcoupon.infrastructure.persistence.po.CouponShareDetailPO;
import com.newcoupon.infrastructure.persistence.po.CouponSettlePartyDailyBillPO;
import com.newcoupon.open.api.dto.CalculateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 环 3 · 按结算主体日账单验收。
 *
 * <p>验证：
 * <ol>
 *   <li>平台券跨 A/B/__default__ 三行 → 主体账单三行；按模板相加 = 模板账单；</li>
 *   <li>跨日退款记退款日、形成负数；原核销日不再出现该冲回；</li>
 *   <li>旧负分账行兼容：`-ABS` 修正后净额减少而不是虚增；</li>
 *   <li>重跑幂等：金额/行数不变。</li>
 * </ol>
 */
@TestPropertySource(properties = "coupon.trade.settle-party.enabled=true")
class SettlePartyDailyBillIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private DailyBillService dailyBillService;
    @Autowired private CouponDailyBillMapper dailyBillMapper;
    @Autowired private CouponSettlePartyDailyBillMapper settlePartyBillMapper;
    @Autowired private CouponShareDetailMapper shareDetailMapper;
    @Autowired private DataSource dataSource;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private record Confirmed(long templateId, long couponId, String usageNo) {
    }

    /** 平台券满100减20；三行 A(12000)/B(3000)/未归属(1000)，分摊 1500/375/125 */
    private Confirmed confirmThreeParties(String suffix) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest(
                        "主体账单-" + suffix, 3400L, 1, 2000L, null, 10000L,
                        10L, 1, 1, start, end, null, null, null));
        templateAppService.publish(created.id());

        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("party-bill-" + suffix, userId, created.id()));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-PARTY-BILL-" + suffix, userId, rcv.couponId()));
        tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null, List.of(
                new CalculateRequest.OrderItem("SKU_A", "CAT1", 6000L, 2, "L1", "A"),
                new CalculateRequest.OrderItem("SKU_B", "CAT1", 3000L, 1, "L2", "B"),
                new CalculateRequest.OrderItem("SKU_C", "CAT1", 1000L, 1, "L3", null))));
        return new Confirmed(created.id(), rcv.couponId(), lock.usageNo());
    }

    private CouponDailyBillPO templateBill(LocalDate date, long templateId) {
        return dailyBillMapper.selectByDate(date).stream()
                .filter(b -> b.getTemplateId() == templateId)
                .findFirst().orElseThrow();
    }

    private List<CouponSettlePartyDailyBillPO> partyBills(LocalDate date, long templateId) {
        return settlePartyBillMapper.selectByFilters(date, null, null, templateId, 100, 0);
    }

    @Test
    @DisplayName("S1: 三个主体行相加 = 模板账单；金额/行数守恒；重跑幂等")
    void threePartiesSumToTemplateBill() {
        Confirmed c = confirmThreeParties("S1");
        LocalDate today = LocalDate.now();
        dailyBillService.generateAll(today);

        List<CouponSettlePartyDailyBillPO> parties = partyBills(today, c.templateId());
        assertThat(parties).hasSize(3);
        assertThat(parties).extracting(CouponSettlePartyDailyBillPO::getSettlePartyId)
                .containsExactlyInAnyOrder("A", "B", "__default__");

        CouponDailyBillPO template = templateBill(today, c.templateId());
        assertThat(parties.stream().mapToLong(CouponSettlePartyDailyBillPO::getDiscountTotal).sum())
                .as("主体金额相加必须等于模板账单").isEqualTo(template.getDiscountTotal());
        assertThat(parties.stream().mapToLong(CouponSettlePartyDailyBillPO::getConfirmCount).sum())
                .as("主体核销行数相加必须等于模板账单")
                .isEqualTo(template.getConfirmCount().longValue());
        assertThat(parties.stream().mapToLong(CouponSettlePartyDailyBillPO::getPlatformBurden).sum())
                .isEqualTo(template.getPlatformBurden());
        assertThat(template.getDiscountTotal()).isEqualTo(2000L);
        assertThat(dailyBillService.reconcile(today))
                .as("主体账单与模板账单对账一致").isZero();

        // 金额落在对应主体：A 1500、B 375、未归属 125
        assertThat(parties.stream().filter(p -> "A".equals(p.getSettlePartyId()))
                .findFirst().orElseThrow().getDiscountTotal()).isEqualTo(1500L);
        assertThat(parties.stream().filter(p -> "B".equals(p.getSettlePartyId()))
                .findFirst().orElseThrow().getDiscountTotal()).isEqualTo(375L);
        assertThat(parties.stream().filter(p -> "__default__".equals(p.getSettlePartyId()))
                .findFirst().orElseThrow().getDiscountTotal()).isEqualTo(125L);

        // 重跑幂等
        dailyBillService.generateAll(today);
        assertThat(partyBills(today, c.templateId())).hasSize(3);
        assertThat(partyBills(today, c.templateId()).stream()
                .mapToLong(CouponSettlePartyDailyBillPO::getDiscountTotal).sum())
                .isEqualTo(2000L);
    }

    @Test
    @DisplayName("S2: 跨日退款记退款日负数，原核销日只保留正行")
    void crossDayRefundGoesToRefundDate() {
        Confirmed c = confirmThreeParties("S2");
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        tradeAppService.refund(new RefundRequest(c.usageNo(), "RF-PARTY-BILL-S2",
                List.of(new RefundItem("L1", 1))));   // L1 优惠 1500/2 → 本次冲回 750

        // 把退款明细挪到昨天，模拟 T+1 售后；账单按 create_time 归日
        new JdbcTemplate(dataSource).update(
                "UPDATE coupon_refund_detail SET create_time = ? WHERE refund_no = ?",
                yesterday.atTime(12, 0), "RF-PARTY-BILL-S2");

        dailyBillService.generateAll(today);
        dailyBillService.generateAll(yesterday);

        CouponSettlePartyDailyBillPO yesterdayA = partyBills(yesterday, c.templateId()).stream()
                .filter(p -> "A".equals(p.getSettlePartyId())).findFirst().orElseThrow();
        assertThat(yesterdayA.getRefundCount()).isEqualTo(1);
        assertThat(yesterdayA.getDiscountTotal()).as("退款日记负数").isEqualTo(-750L);

        CouponSettlePartyDailyBillPO todayA = partyBills(today, c.templateId()).stream()
                .filter(p -> "A".equals(p.getSettlePartyId())).findFirst().orElseThrow();
        assertThat(todayA.getConfirmCount()).isEqualTo(1);
        assertThat(todayA.getRefundCount()).as("退款已归昨天，今天不重复冲回").isZero();
        assertThat(todayA.getDiscountTotal()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("S3: 旧负分账行按 -ABS 净额减少，不虚增")
    void legacyNegativeShareRowNetsOut() {
        Confirmed c = confirmThreeParties("S3");
        LocalDate today = LocalDate.now();

        CouponShareDetailPO legacy = new CouponShareDetailPO();
        legacy.setUserCouponId(c.couponId());
        legacy.setUserId(System.nanoTime());
        legacy.setTemplateId(c.templateId());
        legacy.setAppId("1");
        legacy.setSettlePartyId("A");
        legacy.setUsageNo("LEGACY-" + c.usageNo());
        legacy.setOrderNo("LEGACY-ORDER");
        legacy.setOrderLineNo("L1");
        legacy.setOrderLineAmount(100L);
        legacy.setOrderLineQuantity(1);
        legacy.setPlatformBurden(-100L);
        legacy.setMerchantBurden(0L);
        legacy.setShareTotal(-100L);
        shareDetailMapper.insert(legacy);

        dailyBillService.generateAll(today);

        assertThat(templateBill(today, c.templateId()).getDiscountTotal())
                .as("模板账单 = 2000 - 100").isEqualTo(1900L);
        CouponSettlePartyDailyBillPO a = partyBills(today, c.templateId()).stream()
                .filter(p -> "A".equals(p.getSettlePartyId())).findFirst().orElseThrow();
        assertThat(a.getDiscountTotal()).as("A = 1500 - 100").isEqualTo(1400L);
        assertThat(a.getRefundCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("S5: 主体大小写敏感——A 与 a 不能合并成一行")
    void settlePartyIsCaseSensitive() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest(
                        "主体大小写-S5", 3401L, 1, 1000L, null, 5000L,
                        10L, 1, 1, start, end, null, null, null));
        templateAppService.publish(created.id());

        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("party-case-S5", userId, created.id()));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-PARTY-CASE-S5", userId, rcv.couponId()));
        tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null, List.of(
                new CalculateRequest.OrderItem("SKU_A", "CAT1", 6000L, 1, "L1", "A"),
                new CalculateRequest.OrderItem("SKU_A", "CAT1", 4000L, 1, "L2", "a"))));

        LocalDate today = LocalDate.now();
        dailyBillService.generateAll(today);

        List<CouponSettlePartyDailyBillPO> parties = partyBills(today, created.id());
        assertThat(parties).hasSize(2);
        assertThat(parties).extracting(CouponSettlePartyDailyBillPO::getSettlePartyId)
                .containsExactlyInAnyOrder("A", "a");
        assertThat(parties.stream().mapToLong(CouponSettlePartyDailyBillPO::getDiscountTotal).sum())
                .isEqualTo(1000L);
    }

    @Test
    @DisplayName("S4: 人为改坏主体账单 → reconcile 报不一致；重跑后恢复")
    void reconcileDetectsMismatch() {
        Confirmed c = confirmThreeParties("S4");
        LocalDate today = LocalDate.now();
        dailyBillService.generateAll(today);
        assertThat(dailyBillService.reconcile(today)).isZero();

        new JdbcTemplate(dataSource).update(
                "UPDATE coupon_settle_party_daily_bill SET discount_total = discount_total + 1 "
                        + "WHERE bill_date = ? AND settle_party_id = 'A' AND template_id = ?",
                today, c.templateId());
        assertThat(dailyBillService.reconcile(today))
                .as("主体账单被改坏后对账必须报不一致").isEqualTo(1);

        dailyBillService.generateAll(today);
        assertThat(dailyBillService.reconcile(today))
                .as("重跑 upsert 后恢复一致").isZero();
    }
}
