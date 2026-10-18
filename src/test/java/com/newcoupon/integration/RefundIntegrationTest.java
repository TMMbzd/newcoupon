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
import com.newcoupon.application.dto.TradeDtos.RefundResult;
import com.newcoupon.application.service.DailyBillService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundDetailMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundOrderMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponReturnLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
import com.newcoupon.infrastructure.persistence.po.CouponDailyBillPO;
import com.newcoupon.infrastructure.persistence.po.CouponRefundDetailPO;
import com.newcoupon.infrastructure.persistence.po.CouponShareDetailPO;
import com.newcoupon.open.api.dto.CalculateRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 行级分账 + 按行部分退款 · 集成验收（2026-10-18 行业标准版）。
 *
 * <p>验证点：
 * <ol>
 *   <li>核销按行落库：行号、行金额、行数量、行优惠、平台/商家出资逐行守恒</li>
 *   <li>部分退款：按行 + 数量退，金额读核销快照，不按当前规则重算</li>
 *   <li>数量尾差：最后一个单位拿余数（1000 分 / 3 件 → 333 + 333 + 334）</li>
 *   <li>幂等：同 refundNo 同参重放返回首次结果；同号异参报冲突</li>
 *   <li>累计不超原数量；部分退款券保持已核销；整单退完券回可用</li>
 *   <li>日账单：正行 - 退款明细净额归零</li>
 * </ol>
 */
class RefundIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired private TemplateAppService templateAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private DailyBillService dailyBillService;
    @Autowired private CouponUsageRepository couponUsageRepository;
    @Autowired private UserCouponRepository userCouponRepository;
    @Autowired private CouponShareDetailMapper shareDetailMapper;
    @Autowired private CouponRefundOrderMapper refundOrderMapper;
    @Autowired private CouponRefundDetailMapper refundDetailMapper;
    @Autowired private CouponReturnLogMapper returnLogMapper;
    @Autowired private CouponDailyBillMapper billMapper;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private record Confirmed(long templateId, String usageNo, long couponId) {
    }

    /** 建“满100减20”→领→锁→按两行核销：L1 6000×2、L2 4000×2（优惠分摊 1200/800） */
    private Confirmed confirmTwoLines(String suffix) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest(
                        "退款测试-" + suffix, 3100L, 1, 2000L, null, 10000L,
                        10L, 1, 1, start, end, null, null, null));
        templateAppService.publish(created.id());

        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("refund-" + suffix, userId, created.id()));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-REFUND-" + suffix, userId, rcv.couponId()));
        tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null, List.of(
                new CalculateRequest.OrderItem("SKU_A", "CAT1", 6000L, 2, "L1"),
                new CalculateRequest.OrderItem("SKU_B", "CAT1", 4000L, 2, "L2"))));
        return new Confirmed(created.id(), lock.usageNo(), rcv.couponId());
    }

    @Test
    @DisplayName("R1: 核销按行落库——两条行级分账，行号/行金额/数量/优惠守恒")
    void r1_confirmPersistsLineShares() {
        Confirmed c = confirmTwoLines("R1");

        List<CouponShareDetailPO> shares = shareDetailMapper.selectPositiveByUsageNo(c.usageNo());
        assertThat(shares).hasSize(2);

        CouponShareDetailPO l1 = shares.stream()
                .filter(s -> "L1".equals(s.getOrderLineNo())).findFirst().orElseThrow();
        CouponShareDetailPO l2 = shares.stream()
                .filter(s -> "L2".equals(s.getOrderLineNo())).findFirst().orElseThrow();

        assertThat(l1.getOrderLineAmount()).isEqualTo(12000L);
        assertThat(l1.getOrderLineQuantity()).isEqualTo(2);
        assertThat(l1.getShareTotal()).isEqualTo(1200L);
        assertThat(l1.getPlatformBurden()).isEqualTo(1200L);
        assertThat(l1.getMerchantBurden()).isZero();

        assertThat(l2.getOrderLineAmount()).isEqualTo(8000L);
        assertThat(l2.getOrderLineQuantity()).isEqualTo(2);
        assertThat(l2.getShareTotal()).isEqualTo(800L);

        assertThat(shares.stream().mapToLong(CouponShareDetailPO::getShareTotal).sum())
                .as("行优惠合计 = 券优惠 2000").isEqualTo(2000L);
    }

    @Test
    @DisplayName("R2: 部分退款按行+数量——退 L1 一件 = 600；券保持已核销、占用单部分退款")
    void r2_partialRefundByLineAndQuantity() {
        Confirmed c = confirmTwoLines("R2");

        RefundResult result = tradeAppService.refund(new RefundRequest(
                c.usageNo(), "RF-R2-1", List.of(new RefundItem("L1", 1))));

        assertThat(result.refundType()).isEqualTo("PARTIAL");
        assertThat(result.discountAmt()).as("L1 行优惠 1200 / 2 件 = 每件 600").isEqualTo(600L);
        assertThat(result.usageStatus()).isEqualTo(UsageStatus.PARTIALLY_REFUNDED.getDesc());
        assertThat(result.couponStatus()).isEqualTo(CouponStatus.USED.getDesc());

        assertThat(couponUsageRepository.findByUsageNo(c.usageNo()).orElseThrow().getStatus())
                .isEqualTo(UsageStatus.PARTIALLY_REFUNDED);
        assertThat(userCouponRepository.findById(c.couponId()).orElseThrow().getStatus())
                .as("部分退款不退券").isEqualTo(CouponStatus.USED);

        List<CouponRefundDetailPO> details = refundDetailMapper.selectByRefundNo("RF-R2-1");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).getOrderLineNo()).isEqualTo("L1");
        assertThat(details.get(0).getRefundQty()).isEqualTo(1);
        assertThat(details.get(0).getRefundDiscountAmt()).isEqualTo(600L);
        assertThat(details.get(0).getRefundPlatformAmt()).isEqualTo(600L);
    }

    @Test
    @DisplayName("R3: 退款幂等——同号同参重放返回首次结果；同号异参报冲突；累计数量不超原数量")
    void r3_idempotencyAndOverRefundGuard() {
        Confirmed c = confirmTwoLines("R3");
        RefundRequest first = new RefundRequest(
                c.usageNo(), "RF-R3-1", List.of(new RefundItem("L1", 1)));
        RefundResult r1 = tradeAppService.refund(first);
        assertThat(r1.duplicated()).isFalse();

        RefundResult replay = tradeAppService.refund(first);
        assertThat(replay.duplicated()).isTrue();
        assertThat(replay.discountAmt()).isEqualTo(600L);
        assertThat(refundDetailMapper.selectByRefundNo("RF-R3-1"))
                .as("重放不产生第二条明细").hasSize(1);

        assertThatThrownBy(() -> tradeAppService.refund(new RefundRequest(
                c.usageNo(), "RF-R3-1", List.of(new RefundItem("L1", 2)))))
                .as("同一退款单号不同参数 → 冲突")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("参数不一致");

        assertThatThrownBy(() -> tradeAppService.refund(new RefundRequest(
                c.usageNo(), "RF-R3-2", List.of(new RefundItem("L1", 2)))))
                .as("L1 原数量 2、已退 1，再退 2 超量")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过原数量");
    }

    @Test
    @DisplayName("R4: 数量尾差——单行 1000 分 / 3 件：333 + 333 + 334，最后一件拿余数")
    void r4_quantityRemainder() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest(
                        "尾差测试", 3101L, 3, 1000L, null, 0L,
                        10L, 1, 1, start, end, null, null, null));
        templateAppService.publish(created.id());
        long userId = System.nanoTime();
        ReceiveResult rcv = receiveAppService.receive(
                new ReceiveRequest("refund-remainder", userId, created.id()));
        LockResult lock = tradeAppService.lock(
                new LockRequest("ORD-REFUND-REMAINDER", userId, rcv.couponId()));
        tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), null, List.of(
                new CalculateRequest.OrderItem("SKU_A", "CAT1", 1000L, 3, "L1"))));

        RefundResult first = tradeAppService.refund(new RefundRequest(
                lock.usageNo(), "RF-REM-1", List.of(new RefundItem("L1", 1))));
        RefundResult second = tradeAppService.refund(new RefundRequest(
                lock.usageNo(), "RF-REM-2", List.of(new RefundItem("L1", 1))));
        RefundResult third = tradeAppService.refund(new RefundRequest(
                lock.usageNo(), "RF-REM-3", List.of(new RefundItem("L1", 1))));

        assertThat(first.discountAmt()).isEqualTo(333L);
        assertThat(second.discountAmt()).isEqualTo(333L);
        assertThat(third.discountAmt()).as("最后一件拿 1 分余数").isEqualTo(334L);
        assertThat(first.discountAmt() + second.discountAmt() + third.discountAmt())
                .isEqualTo(1000L);
        assertThat(third.usageStatus()).isEqualTo(UsageStatus.REFUNDED.getDesc());
    }

    @Test
    @DisplayName("R5: 整单退完——占用单已退款、券在有效期内回可用、退券留痕一次、日账单净额归零")
    void r5_fullRefundReturnsCouponAndBillNetsOut() {
        Confirmed c = confirmTwoLines("R5");

        tradeAppService.refund(new RefundRequest(
                c.usageNo(), "RF-R5-1", List.of(new RefundItem("L1", 1))));
        RefundResult full = tradeAppService.refund(new RefundRequest(
                c.usageNo(), "RF-R5-2",
                List.of(new RefundItem("L1", 1), new RefundItem("L2", 2))));

        assertThat(full.refundType()).isEqualTo("FULL");
        assertThat(full.discountAmt()).as("L1 剩 600 + L2 800").isEqualTo(1400L);
        assertThat(full.usageStatus()).isEqualTo(UsageStatus.REFUNDED.getDesc());
        assertThat(full.couponStatus()).as("整单退且券在有效期内 → 回到待使用")
                .isEqualTo(CouponStatus.USABLE.getDesc());

        assertThat(couponUsageRepository.findByUsageNo(c.usageNo()).orElseThrow().getStatus())
                .isEqualTo(UsageStatus.REFUNDED);
        assertThat(userCouponRepository.findById(c.couponId()).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USABLE);
        assertThat(returnLogMapper.countByCoupon(c.couponId())).as("退券留痕一次").isEqualTo(1);

        LocalDate today = LocalDate.now();
        dailyBillService.generateBill(today);
        CouponDailyBillPO bill = billMapper.selectByDate(today).stream()
                .filter(b -> b.getTemplateId() == c.templateId()).findFirst().orElseThrow();
        assertThat(bill.getDiscountTotal()).as("核销 2000 - 退款 2000 = 0").isZero();
        assertThat(bill.getPlatformBurden()).isZero();
        assertThat(bill.getConfirmCount()).isEqualTo(2);
        assertThat(bill.getRefundCount()).as("三次退款共 3 条退款明细行").isEqualTo(3);
    }

    @Test
    @DisplayName("R8: 退掉 L1 后剩余金额跌破门槛 → 不追回已享受优惠，只按行冲回 1200")
    void r8_belowThresholdDoesNotClawBack() {
        Confirmed c = confirmTwoLines("R8");

        RefundResult result = tradeAppService.refund(new RefundRequest(
                c.usageNo(), "RF-R8-1", List.of(new RefundItem("L1", 2))));

        assertThat(result.discountAmt())
                .as("只冲回 L1 的 1200；剩余 L2 80 元 < 门槛 100 元，也不追回差额")
                .isEqualTo(1200L);
        assertThat(result.usageStatus()).isEqualTo(UsageStatus.PARTIALLY_REFUNDED.getDesc());
        assertThat(result.couponStatus()).as("部分退款不退券").isEqualTo(CouponStatus.USED.getDesc());
    }

    @Test
    @DisplayName("R7: 某行已退满后再整单退剩余——跳过已退满行，只退剩余行")
    void r7_fullRefundSkipsAlreadyRefundedLines() {
        Confirmed c = confirmTwoLines("R7");

        tradeAppService.refund(new RefundRequest(
                c.usageNo(), "RF-R7-1", List.of(new RefundItem("L1", 2))));
        assertThat(couponUsageRepository.findByUsageNo(c.usageNo()).orElseThrow().getStatus())
                .as("L1 退满、L2 未退 → 部分退款 ")
                .isEqualTo(UsageStatus.PARTIALLY_REFUNDED);

        RefundResult full = tradeAppService.refund(
                new RefundRequest(c.usageNo(), "RF-R7-2", null));

        assertThat(full.refundType()).isEqualTo("FULL");
        assertThat(full.discountAmt()).as("L1 已退满，本次只退 L2 的 800").isEqualTo(800L);
        assertThat(full.usageStatus()).isEqualTo(UsageStatus.REFUNDED.getDesc());
    }

    @Test
    @DisplayName("R6: 退款单号已用于其他占用单 → 参数冲突，不会串单退款")
    void r6_refundNoCannotBeReusedAcrossUsages() {
        Confirmed a = confirmTwoLines("R6a");
        Confirmed b = confirmTwoLines("R6b");

        tradeAppService.refund(new RefundRequest(
                a.usageNo(), "RF-SHARED", List.of(new RefundItem("L1", 1))));
        assertThatThrownBy(() -> tradeAppService.refund(new RefundRequest(
                b.usageNo(), "RF-SHARED", List.of(new RefundItem("L1", 1)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("其他占用单");
    }

    @Test
    @DisplayName("R10: 并发同号退款 → 一次成功一次重放，不能 500")
    void r10_concurrentSameRefundNo() throws Exception {
        Confirmed c = confirmTwoLines("R10");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        java.util.concurrent.Callable<RefundResult> call = () -> {
            ready.countDown();
            go.await();
            return tradeAppService.refund(new RefundRequest(c.usageNo(), "RF-R10",
                    List.of(new RefundItem("L1", 1))));
        };
        Future<RefundResult> f1 = pool.submit(call);
        Future<RefundResult> f2 = pool.submit(call);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        RefundResult r1 = f1.get(30, TimeUnit.SECONDS);
        RefundResult r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(r1.duplicated() || r2.duplicated())
                .as("并发同号必须有一个走重放，不能两个都当首次成功").isTrue();
        assertThat(r1.discountAmt()).isEqualTo(r2.discountAmt());
        assertThat(refundOrderMapper.selectByRefundNo("RF-R10")).isNotNull();
    }

    @Test
    @DisplayName("R9: 已退 1 件后再传 Integer.MAX_VALUE，不能 int 溢出绕过累计校验")
    void r9_quantityOverflowRejected() {
        Confirmed c = confirmTwoLines("R9");
        tradeAppService.refund(new RefundRequest(
                c.usageNo(), "RF-R9-1", List.of(new RefundItem("L1", 1))));

        assertThatThrownBy(() -> tradeAppService.refund(new RefundRequest(
                c.usageNo(), "RF-R9-2",
                List.of(new RefundItem("L1", Integer.MAX_VALUE)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过原数量");
    }
}
