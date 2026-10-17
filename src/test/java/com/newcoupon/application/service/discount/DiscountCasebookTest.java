package com.newcoupon.application.service.discount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.domain.coupon.ApplicableScope;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.StackingLayer;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.integration.ShardedIntegrationTestSupport;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.open.api.dto.CalculateResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * 优惠计算与分摊·例题全集配套验证（2026-10-16）。
 *
 * <p>用途：为《环2-优惠计算与分摊-例题全集》文档提供"每个例子都拿真实引擎跑过"的答案校对——
 * 本类只补既有 28 条黄金用例没有覆盖到的角落（入门用例 T1~T19/X1~X7 见
 * {@link DiscountCalculatorServiceTest}），两类合起来覆盖引擎全部特性：
 * <ul>
 *   <li>例1  三行除不尽 → 末行取余数（333/333/334）</li>
 *   <li>例2  递进门槛·拒：SHOP 折后金额不够 PLATFORM 门槛</li>
 *   <li>例3  递进门槛·过（边界等号）：SHOP 折后恰好等于 PLATFORM 门槛</li>
 *   <li>例4  同层都没配互斥组 → 默认互斥取大；落选者不进"不可用列表"</li>
 *   <li>例5  满折四舍五入：8.5 折 × 199 分 → 30 分</li>
 *   <li>例6  门槛恰好等于订单金额 → 可用（边界）</li>
 *   <li>例7  单品券范围不匹配 → 不可用</li>
 *   <li>例8  综合：品类券 + 全场券跨层叠加 × 范围感知分摊（1600/1066/334）</li>
 *   <li>例9  总优惠封顶 × 多行分摊：分摊合计必须仍等于封顶后的总优惠（本类先红后绿修出的缺口）</li>
 *   <li>例10 范围券门槛按范围内金额（混单里 FOOD 不足门槛 → 不可用）</li>
 *   <li>例11 范围满折的折扣基数按范围内金额（食品9折只算 FOOD 金额）</li>
 *   <li>例12 单行上限与溢出再分配（三行各1分、优惠2分 → 1/0/1）</li>
 *   <li>例13 同一 SKU 多行用行号区分（不再合并到第一行）</li>
 * </ul>
 */
class DiscountCasebookTest extends ShardedIntegrationTestSupport {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0, 0);

    private static final String APP = "app-A";
    private static final Long USER = 100L;

    @Autowired
    private DiscountCalculatorService service;

    @MockBean
    private UserCouponRepository userCouponRepository;

    @MockBean
    private CachedTemplateReader templateReader;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    // ---------- 工具（与 DiscountCalculatorServiceTest 同构） ----------

    private static CouponTemplate tpl(long id, CouponType type, long faceValue, Long cap,
                                      long threshold, String name) {
        return CouponTemplate.create(CouponTemplate.Creation.builder()
                .templateCode("T" + id).activityId(1L).name(name).couponType(type)
                .faceValue(faceValue).discountCap(cap).threshold(threshold)
                .totalCount(1000L).perUserLimit(1)
                .dailyLimit(0L).perUserDailyLimit(0)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.of(2026, 1, 1, 0, 0))
                .validEnd(LocalDateTime.of(2026, 12, 31, 23, 59, 59))
                .appId(APP)
                .build());
    }

    private static CouponTemplate tplLayer(long id, CouponType type, long faceValue, Long cap,
                                            long threshold, String name, StackingLayer layer) {
        return CouponTemplate.create(CouponTemplate.Creation.builder()
                .templateCode("T" + id).activityId(1L).name(name).couponType(type)
                .faceValue(faceValue).discountCap(cap).threshold(threshold)
                .totalCount(100L).perUserLimit(1).dailyLimit(0L).perUserDailyLimit(0)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.of(2026, 1, 1, 0, 0))
                .validEnd(LocalDateTime.of(2026, 12, 31, 23, 59, 59))
                .appId(APP)
                .stackingLayer(layer)
                .build());
    }

    private static CouponTemplate tplCategory(long id, long faceValue, long threshold,
                                              String name, String scopeIds) {
        return CouponTemplate.create(CouponTemplate.Creation.builder()
                .templateCode("T" + id).activityId(1L).name(name).couponType(CouponType.FULL_REDUCTION)
                .faceValue(faceValue).discountCap(null).threshold(threshold)
                .totalCount(100L).perUserLimit(1).dailyLimit(0L).perUserDailyLimit(0)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.of(2026, 1, 1, 0, 0))
                .validEnd(LocalDateTime.of(2026, 12, 31, 23, 59, 59))
                .appId(APP)
                .applicableScope(ApplicableScope.CATEGORY)
                .scopeIds(scopeIds)
                .build());
    }

    private static CouponTemplate tplSku(long id, long faceValue, long threshold,
                                         String name, String scopeIds) {
        return CouponTemplate.create(CouponTemplate.Creation.builder()
                .templateCode("T" + id).activityId(1L).name(name).couponType(CouponType.FULL_REDUCTION)
                .faceValue(faceValue).discountCap(null).threshold(threshold)
                .totalCount(100L).perUserLimit(1).dailyLimit(0L).perUserDailyLimit(0)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.of(2026, 1, 1, 0, 0))
                .validEnd(LocalDateTime.of(2026, 12, 31, 23, 59, 59))
                .appId(APP)
                .applicableScope(ApplicableScope.SKU)
                .scopeIds(scopeIds)
                .build());
    }

    private static UserCoupon coupon(long id, long templateId, long faceValue, long threshold,
                                     Long cap) {
        return UserCoupon.reconstitute(id, USER, templateId, "T" + templateId,
                faceValue, threshold, cap, LocalDateTime.of(2026, 12, 31, 23, 59, 59),
                CouponStatus.USABLE, LocalDateTime.of(2026, 10, 1, 12, 0), "idem-" + id, 1, APP);
    }

    private static CalculateRequest.OrderItem line(String sku, String cat, long price) {
        return new CalculateRequest.OrderItem(sku, cat, price, 1);
    }

    // ---------- 例1~例9 ----------

    @Test
    @DisplayName("例1: 三行除不尽 → 前两行按比例取整、末行取余数（333/333/334，合计=1000）")
    void case01_threeLinesRemainderToLast() {
        when(templateReader.get(30L)).thenReturn(Optional.of(
                tpl(30L, CouponType.FULL_REDUCTION, 1000L, null, 3000L, "满30减10")));
        when(userCouponRepository.findByIdForUser(9301L, USER)).thenReturn(Optional.of(
                coupon(9301L, 30L, 1000L, 3000L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU_A", "CAT1", 3333L), line("SKU_B", "CAT1", 3333L), line("SKU_C", "CAT1", 3334L)),
                List.of(9301L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).isEqualTo(1000L);
        assertThat(r.allocations()).extracting(CalculateResponse.LineAllocation::allocatedDiscount)
                .containsExactly(333L, 333L, 334L);
        long sum = r.allocations().stream().mapToLong(CalculateResponse.LineAllocation::allocatedDiscount).sum();
        assertThat(sum).as("分摊合计 = 总优惠").isEqualTo(1000L);
    }

    @Test
    @DisplayName("例2: 递进门槛·拒——SHOP 满100减20 先减完，剩余130元不够 PLATFORM 满140的门槛")
    void case02_platformThresholdMissesAfterShopDiscount() {
        when(templateReader.get(40L)).thenReturn(Optional.of(
                tplLayer(40L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "店铺满100减20", StackingLayer.SHOP)));
        when(templateReader.get(41L)).thenReturn(Optional.of(
                tplLayer(41L, CouponType.FULL_REDUCTION, 3000L, null, 14000L, "平台满140减30", StackingLayer.PLATFORM)));
        when(userCouponRepository.findByIdForUser(9401L, USER)).thenReturn(Optional.of(coupon(9401L, 40L, 2000L, 10000L, null)));
        when(userCouponRepository.findByIdForUser(9402L, USER)).thenReturn(Optional.of(coupon(9402L, 41L, 3000L, 14000L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU1", "CAT1", 15000L)), List.of(9401L, 9402L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).as("只有店铺券生效").isEqualTo(2000L);
        assertThat(r.usedCoupons()).hasSize(1);
        assertThat(r.unusableCoupons()).hasSize(1);
        assertThat(r.unusableCoupons().get(0).couponId()).isEqualTo(9402L);
        assertThat(r.unusableCoupons().get(0).reason())
                .as("门槛按 SHOP 折后的 130 元判，不是原价 150 元").contains("未达使用门槛");
    }

    @Test
    @DisplayName("例3: 递进门槛·过（边界等号）——剩余130元恰好够 PLATFORM 满130的门槛")
    void case03_platformThresholdPassesExactlyOnDiscountedAmount() {
        when(templateReader.get(40L)).thenReturn(Optional.of(
                tplLayer(40L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "店铺满100减20", StackingLayer.SHOP)));
        when(templateReader.get(42L)).thenReturn(Optional.of(
                tplLayer(42L, CouponType.FULL_REDUCTION, 3000L, null, 13000L, "平台满130减30", StackingLayer.PLATFORM)));
        when(userCouponRepository.findByIdForUser(9401L, USER)).thenReturn(Optional.of(coupon(9401L, 40L, 2000L, 10000L, null)));
        when(userCouponRepository.findByIdForUser(9403L, USER)).thenReturn(Optional.of(coupon(9403L, 42L, 3000L, 13000L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU1", "CAT1", 15000L)), List.of(9401L, 9403L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).as("2000 + 3000").isEqualTo(5000L);
        assertThat(r.finalPrice()).isEqualTo(10000L);
        assertThat(r.usedCoupons()).hasSize(2);
    }

    @Test
    @DisplayName("例4: 同层都没配互斥组 → 默认互斥取大（3000>2000）；落选者不进不可用列表")
    void case04_noGroupMeansDefaultExclusion() {
        when(templateReader.get(50L)).thenReturn(Optional.of(
                tpl(50L, CouponType.FULL_REDUCTION, 3000L, null, 10000L, "满100减30")));
        when(templateReader.get(51L)).thenReturn(Optional.of(
                tpl(51L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20")));
        when(userCouponRepository.findByIdForUser(9501L, USER)).thenReturn(Optional.of(coupon(9501L, 50L, 3000L, 10000L, null)));
        when(userCouponRepository.findByIdForUser(9502L, USER)).thenReturn(Optional.of(coupon(9502L, 51L, 2000L, 10000L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU1", "CAT1", 15000L)), List.of(9501L, 9502L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).as("取大 3000").isEqualTo(3000L);
        assertThat(r.usedCoupons()).hasSize(1);
        assertThat(r.usedCoupons().get(0).couponId()).isEqualTo(9501L);
        assertThat(r.unusableCoupons())
                .as("落选不是不可用——它是被同组规则淘汰的，不进不可用列表").isEmpty();
    }

    @Test
    @DisplayName("例5: 满折四舍五入——8.5折 × 199分：(199×1500+5000)/10000 = 30分")
    void case05_discountRounding() {
        when(templateReader.get(60L)).thenReturn(Optional.of(
                tpl(60L, CouponType.DISCOUNT, 8500L, 5000L, 0L, "8.5折")));
        when(userCouponRepository.findByIdForUser(9601L, USER)).thenReturn(Optional.of(coupon(9601L, 60L, 8500L, 0L, 5000L)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU1", "CAT1", 199L)), List.of(9601L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).isEqualTo(30L);
        assertThat(r.finalPrice()).isEqualTo(169L);
    }

    @Test
    @DisplayName("例6: 门槛恰好等于订单金额 → 可用（边界；判定是'小于才拒'）")
    void case06_thresholdExactBoundary() {
        when(templateReader.get(70L)).thenReturn(Optional.of(
                tpl(70L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20")));
        when(userCouponRepository.findByIdForUser(9701L, USER)).thenReturn(Optional.of(coupon(9701L, 70L, 2000L, 10000L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU1", "CAT1", 10000L)), List.of(9701L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).isEqualTo(2000L);
        assertThat(r.finalPrice()).isEqualTo(8000L);
        assertThat(r.usedCoupons()).hasSize(1);
    }

    @Test
    @DisplayName("例7: 单品券范围不匹配（券限 SKU_X，订单是 SKU_Y）→ 不可用")
    void case07_skuScopeMismatch() {
        when(templateReader.get(80L)).thenReturn(Optional.of(
                tplSku(80L, 2000L, 5000L, "单品券", "[\"SKU_X\"]")));
        when(userCouponRepository.findByIdForUser(9801L, USER)).thenReturn(Optional.of(coupon(9801L, 80L, 2000L, 5000L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU_Y", "CAT1", 15000L)), List.of(9801L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).isEqualTo(0L);
        assertThat(r.usedCoupons()).isEmpty();
        assertThat(r.unusableCoupons()).hasSize(1);
        assertThat(r.unusableCoupons().get(0).reason()).contains("适用范围");
    }

    @Test
    @DisplayName("例8: 综合——品类券(FOOD满50减20) + 全场券(平台满100减10)，跨层叠加×范围感知分摊")
    void case08_comprehensiveScopesAndLayers() {
        when(templateReader.get(90L)).thenReturn(Optional.of(
                tplCategory(90L, 2000L, 5000L, "食品品类券", "[\"FOOD\"]")));
        when(templateReader.get(91L)).thenReturn(Optional.of(
                tplLayer(91L, CouponType.FULL_REDUCTION, 1000L, null, 10000L, "平台满100减10", StackingLayer.PLATFORM)));
        when(userCouponRepository.findByIdForUser(9901L, USER)).thenReturn(Optional.of(coupon(9901L, 90L, 2000L, 5000L, null)));
        when(userCouponRepository.findByIdForUser(9902L, USER)).thenReturn(Optional.of(coupon(9902L, 91L, 1000L, 10000L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU_F1", "FOOD", 6000L),
                        line("SKU_F2", "FOOD", 4000L),
                        line("SKU_D1", "DIGITAL", 5000L)),
                List.of(9901L, 9902L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).as("品类券2000 + 全场券1000").isEqualTo(3000L);
        assertThat(r.finalPrice()).isEqualTo(12000L);
        assertThat(r.usedCoupons()).hasSize(2);
        // 品类券只分给 FOOD 两行（1200 + 800），扣完后三行剩余 4800/3200/5000；
        // 全场券按“进入本层前的剩余金额”分：F1=369、F2=246、D1=385（末行兜底）。
        assertThat(r.allocations()).extracting(CalculateResponse.LineAllocation::skuId)
                .containsExactly("SKU_F1", "SKU_F2", "SKU_D1");
        assertThat(r.allocations()).extracting(CalculateResponse.LineAllocation::allocatedDiscount)
                .as("F1=1200+369, F2=800+246, D1=385").containsExactly(1569L, 1046L, 385L);
        assertThat(r.allocations().get(0).couponShares())
                .as("行上逐券明细：退款按行 × 券冲回").extracting(CalculateResponse.CouponLineShare::couponId)
                .containsExactly(9901L, 9902L);
        long sum = r.allocations().stream().mapToLong(CalculateResponse.LineAllocation::allocatedDiscount).sum();
        assertThat(sum).as("分摊合计 = 总优惠").isEqualTo(3000L);
    }

    @Test
    @DisplayName("例9: 总优惠封顶时，分摊合计仍必须等于封顶后的总优惠（本类修出的缺口）")
    void case09_capKeepsAllocationConsistent() {
        when(templateReader.get(95L)).thenReturn(Optional.of(
                tplLayer(95L, CouponType.FULL_REDUCTION, 9000L, null, 10000L, "店铺满100减90", StackingLayer.SHOP)));
        when(templateReader.get(96L)).thenReturn(Optional.of(
                tplLayer(96L, CouponType.NO_THRESHOLD, 2000L, null, 0L, "平台立减20", StackingLayer.PLATFORM)));
        when(userCouponRepository.findByIdForUser(9951L, USER)).thenReturn(Optional.of(coupon(9951L, 95L, 9000L, 10000L, null)));
        when(userCouponRepository.findByIdForUser(9952L, USER)).thenReturn(Optional.of(coupon(9952L, 96L, 2000L, 0L, null)));

        // 券面之和 9000 + 2000 = 11000 > 订单 10000 → 总优惠封顶到 10000
        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU_A", "CAT1", 6000L), line("SKU_B", "CAT1", 4000L)),
                List.of(9951L, 9952L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).as("总优惠封顶到订单金额").isEqualTo(10000L);
        assertThat(r.finalPrice()).isEqualTo(0L);
        long sum = r.allocations().stream().mapToLong(CalculateResponse.LineAllocation::allocatedDiscount).sum();
        assertThat(sum).as("分摊合计 = 封顶后的总优惠（资损防线，封顶场景此前会破）").isEqualTo(10000L);
        assertThat(r.allocations()).extracting(CalculateResponse.LineAllocation::allocatedDiscount)
                .as("店铺券拿满9000、平台券按剩余额度拿1000：行A=6000、行B=4000")
                .containsExactly(6000L, 4000L);
    }

    @Test
    @DisplayName("例10: 范围券门槛按范围内金额——FOOD 30元 + DIGITAL 70元，食品券满50 → 不可用")
    void case10_scopeThresholdRejectsMixedOrder() {
        when(templateReader.get(100L)).thenReturn(Optional.of(
                tplCategory(100L, 2000L, 5000L, "食品满50减20", "[\"FOOD\"]")));
        when(userCouponRepository.findByIdForUser(9101L, USER)).thenReturn(Optional.of(
                coupon(9101L, 100L, 2000L, 5000L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU_F1", "FOOD", 3000L), line("SKU_D1", "DIGITAL", 7000L)),
                List.of(9101L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).as("整单100元不算数，FOOD 只有30元").isEqualTo(0L);
        assertThat(r.unusableCoupons()).hasSize(1);
        assertThat(r.unusableCoupons().get(0).reason()).contains("未达使用门槛");
    }

    @Test
    @DisplayName("例11: 范围满折的折扣基数按范围内金额——食品9折 × FOOD 60元 → 优惠6元")
    void case11_scopeDiscountBaseUsesScopeAmount() {
        CouponTemplate foodRate = CouponTemplate.create(CouponTemplate.Creation.builder()
                .templateCode("T101").activityId(1L).name("食品9折")
                .couponType(CouponType.DISCOUNT).faceValue(9000L).discountCap(5000L).threshold(5000L)
                .totalCount(100L).perUserLimit(1).dailyLimit(0L).perUserDailyLimit(0)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.of(2026, 1, 1, 0, 0))
                .validEnd(LocalDateTime.of(2026, 12, 31, 23, 59, 59))
                .appId(APP)
                .applicableScope(ApplicableScope.CATEGORY)
                .scopeIds("[\"FOOD\"]")
                .build());
        when(templateReader.get(101L)).thenReturn(Optional.of(foodRate));
        when(userCouponRepository.findByIdForUser(9102L, USER)).thenReturn(Optional.of(
                coupon(9102L, 101L, 9000L, 5000L, 5000L)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU_F1", "FOOD", 6000L), line("SKU_D1", "DIGITAL", 7000L)),
                List.of(9102L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).as("FOOD 60元 × 10% = 6元，不是整单130元 × 10%").isEqualTo(600L);
        assertThat(r.allocations().get(0).allocatedDiscount()).isEqualTo(600L);
        assertThat(r.allocations().get(1).allocatedDiscount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("例12: 单行上限与溢出再分配——三行各1分、优惠2分 → 1/0/1（每行不超1，合计=2）")
    void case12_perLineCapAndOverflowRedistribution() {
        when(templateReader.get(102L)).thenReturn(Optional.of(
                tpl(102L, CouponType.NO_THRESHOLD, 2L, null, 0L, "立减2分")));
        when(userCouponRepository.findByIdForUser(9103L, USER)).thenReturn(Optional.of(
                coupon(9103L, 102L, 2L, 0L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(line("SKU_A", "CAT1", 1L), line("SKU_B", "CAT1", 1L), line("SKU_C", "CAT1", 1L)),
                List.of(9103L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.discountAmount()).isEqualTo(2L);
        assertThat(r.allocations()).extracting(CalculateResponse.LineAllocation::allocatedDiscount)
                .as("末行装不下2分：装满1分，溢出1分补给第一行").containsExactly(1L, 0L, 1L);
        assertThat(r.allocations()).allSatisfy(a ->
                assertThat(a.allocatedDiscount()).isLessThanOrEqualTo(a.lineAmount()));
    }

    @Test
    @DisplayName("例13: 同一 SKU 多行用行号区分——6000/4000 两行分摊 1200/800，不再合并到第一行")
    void case13_sameSkuMultipleLinesUseLineNo() {
        when(templateReader.get(103L)).thenReturn(Optional.of(
                tpl(103L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20")));
        when(userCouponRepository.findByIdForUser(9104L, USER)).thenReturn(Optional.of(
                coupon(9104L, 103L, 2000L, 10000L, null)));

        CalculateRequest req = new CalculateRequest(USER,
                List.of(new CalculateRequest.OrderItem("SKU_SAME", "CAT1", 6000L, 1, "LINE-1"),
                        new CalculateRequest.OrderItem("SKU_SAME", "CAT1", 4000L, 1, "LINE-2")),
                List.of(9104L));
        CalculateResponse r = service.calculate(req, NOW);

        assertThat(r.allocations()).extracting(CalculateResponse.LineAllocation::lineNo)
                .containsExactly("LINE-1", "LINE-2");
        assertThat(r.allocations()).extracting(CalculateResponse.LineAllocation::allocatedDiscount)
                .containsExactly(1200L, 800L);
    }
}
