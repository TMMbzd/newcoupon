package com.newcoupon.application.service.discount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.application.service.discount.checker.EligibilityChecker;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.ApplicableScope;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.StackingLayer;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
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
 * 优惠计算引擎·黄金用例（T1~T10）+ 防线用例（X1~X5）——2026-10-10 第 0 环返工的验收测试。
 *
 * <p>与旧版测试的三处差别（都是返工裁定的落地）：
 * <ul>
 *   <li><b>挂共享容器基座</b>：旧版是裸 @SpringBootTest（连 dev 库）——依赖本机容器状态；
 *       现在与全部集成测试同基座（Testcontainers），且不再需要 dev 库存活。</li>
 *   <li><b>时间注入</b>：全部用例经 {@code calculate(request, NOW)} 传固定时间——
 *       旧版在券有效期里写死 2026-10-20，过这天全量假红（定时炸弹）。</li>
 *   <li><b>语义对齐十条黄金用例</b>：T7 定死"订单 0 元=无优惠可用"，T9 定死"封顶到订单金额"
 *       （保底价校验属第 2 步配置期防线，不进本组）。</li>
 * </ul>
 *
 * <p>红线证据链：Run A（旧代码）里旧 T7/T10 已经红过（折扣券被按满减算、原因文案错位）；
 * 本组用例按修正后的语义全绿即为"返工转绿"。
 */
class DiscountCalculatorServiceTest extends ShardedIntegrationTestSupport {

    /** 全部用例共用固定时间：2026-10-15 正午。有效期类用例以它为锚构造边界。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0, 0);

    private static final String APP = "app-A";
    private static final Long USER = 100L;

    @Autowired
    private DiscountCalculatorService service;

    /** 直接注入列表本身——证明"集合注入"在任何声明处都给到同一套排好序的零件 */
    @Autowired
    private List<EligibilityChecker> assembledChain;

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

    // ---------- 工具 ----------

    /** 造真实模板领域对象（走 create 全量校验——测试数据本身先过一遍资损不变量）。 */
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

    private static UserCoupon coupon(long id, long templateId, long faceValue, long threshold,
                                     Long cap, LocalDateTime validEnd) {
        return UserCoupon.reconstitute(id, USER, templateId, "T" + templateId,
                faceValue, threshold, cap, validEnd, CouponStatus.USABLE,
                LocalDateTime.of(2026, 10, 1, 12, 0), "idem-" + id, 1, APP);
    }

    private static CalculateRequest req(List<CalculateRequest.OrderItem> items, List<Long> couponIds) {
        return new CalculateRequest(USER, items, couponIds);
    }

    private static CalculateRequest.OrderItem item(long price) {
        return new CalculateRequest.OrderItem("SKU1", "CAT1", price, 1);
    }

    // ---------- 黄金用例 T1~T10 ----------

    @Test
    @DisplayName("T1: 满100减20，订单150元 → 优惠20元，到手130元，券名可读")
    void t1_fullReductionAboveThreshold() {
        CouponTemplate tpl = tpl(1L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20");
        UserCoupon c = coupon(1001L, 1L, 2000L, 10000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1001L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(1L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(15000L)), List.of(1001L)), NOW);

        assertThat(r.originalPrice()).isEqualTo(15000L);
        assertThat(r.discountAmount()).isEqualTo(2000L);
        assertThat(r.finalPrice()).isEqualTo(13000L);
        assertThat(r.usedCoupons()).hasSize(1);
        assertThat(r.usedCoupons().get(0).templateName()).isEqualTo("满100减20");
        assertThat(r.unusableCoupons()).isEmpty();
    }

    @Test
    @DisplayName("T2: 满100减20，订单80元 → 不可用，原因含'未达使用门槛'")
    void t2_belowThreshold() {
        CouponTemplate tpl = tpl(1L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20");
        UserCoupon c = coupon(1001L, 1L, 2000L, 10000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1001L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(1L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(8000L)), List.of(1001L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(0L);
        assertThat(r.finalPrice()).isEqualTo(8000L);
        assertThat(r.unusableCoupons()).hasSize(1);
        assertThat(r.unusableCoupons().get(0).reason()).contains("未达使用门槛");
    }

    @Test
    @DisplayName("T3: 9折券(基点9000,封顶50元)，订单100元 → 优惠10元，到手90元（返工核心：按折扣策略算）")
    void t3_discountCoupon() {
        CouponTemplate tpl = tpl(2L, CouponType.DISCOUNT, 9000L, 5000L, 0L, "9折券");
        UserCoupon c = coupon(1002L, 2L, 9000L, 0L, 5000L,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1002L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(2L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(10000L)), List.of(1002L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(1000L);
        assertThat(r.finalPrice()).isEqualTo(9000L);
        assertThat(r.usedCoupons()).hasSize(1);
    }

    @Test
    @DisplayName("T4: 满减20 vs 9折(优惠15)，订单150元 → 择优满减（2000 > 1500）")
    void t4_chooseBestAmongCoupons() {
        CouponTemplate tplFull = tpl(1L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20");
        CouponTemplate tplRate = tpl(2L, CouponType.DISCOUNT, 9000L, 5000L, 0L, "9折券");
        UserCoupon full = coupon(1001L, 1L, 2000L, 10000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        UserCoupon rate = coupon(1002L, 2L, 9000L, 0L, 5000L,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1001L, USER)).thenReturn(Optional.of(full));
        when(userCouponRepository.findByIdForUser(1002L, USER)).thenReturn(Optional.of(rate));
        when(templateReader.get(1L)).thenReturn(Optional.of(tplFull));
        when(templateReader.get(2L)).thenReturn(Optional.of(tplRate));

        CalculateResponse r = service.calculate(
                req(List.of(item(15000L)), List.of(1002L, 1001L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(2000L);
        assertThat(r.usedCoupons()).hasSize(1);
        assertThat(r.usedCoupons().get(0).couponId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("T5: 有效期止于昨天 → 不可用，原因'券已过期'")
    void t5_expired() {
        CouponTemplate tpl = tpl(1L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20");
        UserCoupon c = coupon(1001L, 1L, 2000L, 10000L, null,
                LocalDateTime.of(2026, 10, 14, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1001L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(1L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(15000L)), List.of(1001L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(0L);
        assertThat(r.unusableCoupons()).hasSize(1);
        assertThat(r.unusableCoupons().get(0).reason()).contains("券已过期");
    }

    @Test
    @DisplayName("T6: 领取时间在明天（未生效）→ 不可用，原因'券未到使用时间'")
    void t6_notYetValid() {
        CouponTemplate tpl = tpl(1L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20");
        UserCoupon c = UserCoupon.reconstitute(1001L, USER, 1L, "T1",
                2000L, 10000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59), CouponStatus.USABLE,
                LocalDateTime.of(2026, 10, 16, 12, 0), "idem-1001", 1, APP);
        when(userCouponRepository.findByIdForUser(1001L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(1L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(15000L)), List.of(1001L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(0L);
        assertThat(r.unusableCoupons().get(0).reason()).contains("券未到使用时间");
    }

    @Test
    @DisplayName("T7: 订单0元 + 无门槛券 → 优惠0，到手0，原因'订单金额为 0，无优惠可用'")
    void t7_zeroOrderAmount() {
        CouponTemplate tpl = tpl(3L, CouponType.NO_THRESHOLD, 1000L, null, 0L, "立减10元");
        UserCoupon c = coupon(1003L, 3L, 1000L, 0L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1003L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(3L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(0L)), List.of(1003L)), NOW);

        assertThat(r.originalPrice()).isEqualTo(0L);
        assertThat(r.discountAmount()).isEqualTo(0L);
        assertThat(r.finalPrice()).isEqualTo(0L);
        assertThat(r.unusableCoupons()).hasSize(1);
        assertThat(r.unusableCoupons().get(0).reason()).contains("订单金额为 0");
    }

    @Test
    @DisplayName("T8: 全部券不可用（门槛未达）→ 优惠0，到手价=原价，无券使用")
    void t8_noCouponAvailable() {
        CouponTemplate tpl = tpl(1L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20");
        UserCoupon c = coupon(1001L, 1L, 2000L, 10000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1001L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(1L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(5000L)), List.of(1001L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(0L);
        assertThat(r.finalPrice()).isEqualTo(5000L);
        assertThat(r.usedCoupons()).isEmpty();
    }

    @Test
    @DisplayName("T9: 满10减20，订单15元 → 优惠封顶到15元，到手0元（语义定死：不设保底价）")
    void t9_discountCappedByOrderAmount() {
        CouponTemplate tpl = tpl(4L, CouponType.FULL_REDUCTION, 2000L, null, 1000L, "满10减20");
        UserCoupon c = coupon(1004L, 4L, 2000L, 1000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1004L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(4L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(1500L)), List.of(1004L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(1500L);
        assertThat(r.finalPrice()).isEqualTo(0L);
        assertThat(r.usedCoupons()).hasSize(1);
        assertThat(r.usedCoupons().get(0).discountAmount()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("T10: 9折券封顶5元，订单100元 → 优惠5元（折扣上限生效）")
    void t10_discountCapApplies() {
        CouponTemplate tpl = tpl(2L, CouponType.DISCOUNT, 9000L, 500L, 0L, "9折封顶5元");
        UserCoupon c = coupon(1002L, 2L, 9000L, 0L, 500L,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1002L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(2L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(10000L)), List.of(1002L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(500L);
        assertThat(r.finalPrice()).isEqualTo(9500L);
    }

    // ---------- 防线与边界用例 X1~X5 ----------

    @Test
    @DisplayName("X1: 无门槛立减10元，订单50元 → 优惠10元（第三种券类型策略接通）")
    void x1_noThresholdCoupon() {
        CouponTemplate tpl = tpl(3L, CouponType.NO_THRESHOLD, 1000L, null, 0L, "立减10元");
        UserCoupon c = coupon(1003L, 3L, 1000L, 0L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1003L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(3L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(5000L)), List.of(1003L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(1000L);
        assertThat(r.finalPrice()).isEqualTo(4000L);
    }

    @Test
    @DisplayName("X2: 越权防线——归属不符对外按'不存在或不可见'应答，与真不存在完全一致（不泄露存在性）")
    void x2_ownershipHiddenNotRevealed() {
        UserCoupon foreign = coupon(1001L, 1L, 2000L, 10000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));   // 归属 app-A
        when(userCouponRepository.findByIdForUser(1001L, USER)).thenReturn(Optional.of(foreign));

        RequestContext.set(new RequestContext("app-B", "trace-x2",
                System.currentTimeMillis() + 30_000, false));
        try {
            CalculateResponse r = service.calculate(req(List.of(item(15000L)), List.of(1001L)), NOW);

            assertThat(r.discountAmount()).isEqualTo(0L);
            assertThat(r.usedCoupons()).isEmpty();
            assertThat(r.unusableCoupons()).hasSize(1);
            // 与 X4（券真不存在）的应答完全一致——探测者无法从应答差异推断"券是否存在"。
            // 服务端信号在安全日志里（OWNERSHIP_VIOLATION 标签），不在应答里。
            assertThat(r.unusableCoupons().get(0).reason()).isEqualTo("券不存在或不可见");
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("X3: 折扣取整——8.5折，订单99.99元 → 整数分四舍五入（9999×1500+5000)/10000=1500")
    void x3_integerRounding() {
        CouponTemplate tpl = tpl(5L, CouponType.DISCOUNT, 8500L, 5000L, 0L, "8.5折");
        UserCoupon c = coupon(1005L, 5L, 8500L, 0L, 5000L,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1005L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(5L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(9999L)), List.of(1005L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(1500L);
        assertThat(r.finalPrice()).isEqualTo(8499L);
    }

    @Test
    @DisplayName("X4: 券不存在 → 不静默丢弃，进不可用列表原因'券不存在或不可见'")
    void x4_couponNotFound() {
        when(userCouponRepository.findByIdForUser(9999L, USER)).thenReturn(Optional.empty());

        CalculateResponse r = service.calculate(req(List.of(item(15000L)), List.of(9999L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(0L);
        assertThat(r.unusableCoupons()).hasSize(1);
        assertThat(r.unusableCoupons().get(0).reason()).isEqualTo("券不存在或不可见");
    }

    @Test
    @DisplayName("X5: 模板解析不到（防御）→ 进不可用列表原因'模板不存在或已删除'")
    void x5_templateMissing() {
        UserCoupon c = coupon(1001L, 1L, 2000L, 10000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(1001L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(1L)).thenReturn(Optional.empty());

        CalculateResponse r = service.calculate(req(List.of(item(15000L)), List.of(1001L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(0L);
        assertThat(r.unusableCoupons().get(0).reason()).isEqualTo("模板不存在或已删除");
    }

    @Test
    @DisplayName("X6: 责任链组装契约——集合注入自动收集三个校验器并按 @Order 排序（顺序=对外文案契约）")
    void x6_chainAssemblyOrderPinned() {
        // containsExactly 同时钉死两件事：成员（恰好这三个，多一个少一个都红）与顺序（时间→状态→门槛）。
        // 新增校验器时此测试必红——强迫作者显式决定它插在哪一环（顺序是对外文案契约，见 3.3.0）。
        assertThat(assembledChain)
                .extracting(c -> c.getClass().getSimpleName())
                .containsExactly("TimeRangeChecker", "StatusChecker", "ThresholdChecker");
    }

    @Test
    @DisplayName("X7: 已锁定的券→不可用'券状态不可用：已锁定'（防双重优惠：券已抵押给另一订单）")
    void x7_lockedCouponRejected() {
        // 已锁定 = 券已抵押给另一份占用单；若允许给新订单算优惠 → 两个订单主张同一张券 → 资损。
        // 券回到可用的路径在锁券侧（释放/超时收场→待使用），试算只反映当前状态。
        UserCoupon locked = UserCoupon.reconstitute(1001L, USER, 1L, "T1",
                2000L, 10000L, null,
                LocalDateTime.of(2026, 12, 31, 23, 59, 59), CouponStatus.LOCKED,
                LocalDateTime.of(2026, 10, 1, 12, 0), "idem-1001", 1, APP);
        when(userCouponRepository.findByIdForUser(1001L, USER)).thenReturn(Optional.of(locked));
        CouponTemplate tpl = tpl(1L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20");
        when(templateReader.get(1L)).thenReturn(Optional.of(tpl));

        CalculateResponse r = service.calculate(req(List.of(item(15000L)), List.of(1001L)), NOW);

        assertThat(r.discountAmount()).isEqualTo(0L);
        assertThat(r.usedCoupons()).isEmpty();
        assertThat(r.unusableCoupons()).hasSize(1);
        assertThat(r.unusableCoupons().get(0).reason()).isEqualTo("优惠券当前不可用: 券状态不可用：已锁定");
    }

    // ════ Ring 2 新增用例（两层叠加 / 同层互斥 / 范围校验 / 行级分摊）════

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

    @Test
    @DisplayName("T11: 两层叠加——SHOP 满100减20 + PLATFORM 9折，订单150元 → 总优惠 2000+1500=3500")
    void t11_twoLayerStacking() {
        CouponTemplate shopTpl = tplLayer(10L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "店铺满减", StackingLayer.SHOP);
        CouponTemplate platTpl = tplLayer(11L, CouponType.DISCOUNT, 9000L, 5000L, 0L, "平台9折", StackingLayer.PLATFORM);
        UserCoupon shopC = coupon(3001L, 10L, 2000L, 10000L, null, LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        UserCoupon platC = coupon(3002L, 11L, 9000L, 0L, 5000L, LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(3001L, USER)).thenReturn(Optional.of(shopC));
        when(userCouponRepository.findByIdForUser(3002L, USER)).thenReturn(Optional.of(platC));
        when(templateReader.get(10L)).thenReturn(Optional.of(shopTpl));
        when(templateReader.get(11L)).thenReturn(Optional.of(platTpl));

        CalculateResponse r = service.calculate(req(List.of(item(15000L)), List.of(3001L, 3002L)), NOW);

        assertThat(r.discountAmount()).as("SHOP 2000 + PLATFORM 1500 = 3500").isEqualTo(3500L);
        assertThat(r.finalPrice()).isEqualTo(11500L);
        assertThat(r.usedCoupons()).as("两层各用一张").hasSize(2);
        assertThat(r.usedCoupons().stream().filter(u -> "SHOP".equals(u.layer()))).hasSize(1);
        assertThat(r.usedCoupons().stream().filter(u -> "PLATFORM".equals(u.layer()))).hasSize(1);
    }

    @Test
    @DisplayName("T12: 同层互斥——两张 SHOP 券只有优惠大的那张生效")
    void t12_sameLayerExclusion() {
        CouponTemplate bigTpl = tplLayer(12L, CouponType.FULL_REDUCTION, 3000L, null, 10000L, "满100减30", StackingLayer.SHOP);
        CouponTemplate smallTpl = tplLayer(13L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20", StackingLayer.SHOP);
        UserCoupon bigC = coupon(4001L, 12L, 3000L, 10000L, null, LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        UserCoupon smallC = coupon(4002L, 13L, 2000L, 10000L, null, LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(4001L, USER)).thenReturn(Optional.of(bigC));
        when(userCouponRepository.findByIdForUser(4002L, USER)).thenReturn(Optional.of(smallC));
        when(templateReader.get(12L)).thenReturn(Optional.of(bigTpl));
        when(templateReader.get(13L)).thenReturn(Optional.of(smallTpl));

        CalculateResponse r = service.calculate(req(List.of(item(15000L)), List.of(4001L, 4002L)), NOW);

        assertThat(r.discountAmount()).as("同层取大：3000 > 2000").isEqualTo(3000L);
        assertThat(r.usedCoupons()).as("同层只用一张").hasSize(1);
        assertThat(r.usedCoupons().get(0).couponId()).isEqualTo(4001L);
    }

    @Test
    @DisplayName("T13: 范围校验——品类券，订单含匹配品类 → 可用")
    void t13_scopeCategoryMatched() {
        CouponTemplate catTpl = CouponTemplate.create(CouponTemplate.Creation.builder()
                .templateCode("T14").activityId(1L).name("食品品类券").couponType(CouponType.FULL_REDUCTION)
                .faceValue(2000L).threshold(10000L).totalCount(100L).perUserLimit(1)
                .dailyLimit(0L).perUserDailyLimit(0).validType(ValidType.FIXED)
                .validStart(LocalDateTime.of(2026, 1, 1, 0, 0))
                .validEnd(LocalDateTime.of(2026, 12, 31, 23, 59, 59))
                .appId(APP).applicableScope(ApplicableScope.CATEGORY).scopeIds("[\"CAT_FOOD\"]").build());
        UserCoupon c = coupon(5001L, 14L, 2000L, 10000L, null, LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(5001L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(14L)).thenReturn(Optional.of(catTpl));

        CalculateRequest catReq = new CalculateRequest(USER,
                List.of(new CalculateRequest.OrderItem("SKU1", "CAT_FOOD", 15000L, 1)), List.of(5001L));
        CalculateResponse r = service.calculate(catReq, NOW);

        assertThat(r.discountAmount()).as("品类匹配 → 券可用").isEqualTo(2000L);
        assertThat(r.usedCoupons()).hasSize(1);
    }

    @Test
    @DisplayName("T14: 范围校验——品类券，订单不含匹配品类 → 不可用")
    void t14_scopeCategoryNotMatched() {
        CouponTemplate catTpl = CouponTemplate.create(CouponTemplate.Creation.builder()
                .templateCode("T15").activityId(1L).name("食品品类券").couponType(CouponType.FULL_REDUCTION)
                .faceValue(2000L).threshold(10000L).totalCount(100L).perUserLimit(1)
                .dailyLimit(0L).perUserDailyLimit(0).validType(ValidType.FIXED)
                .validStart(LocalDateTime.of(2026, 1, 1, 0, 0))
                .validEnd(LocalDateTime.of(2026, 12, 31, 23, 59, 59))
                .appId(APP).applicableScope(ApplicableScope.CATEGORY).scopeIds("[\"CAT_FOOD\"]").build());
        UserCoupon c = coupon(5002L, 15L, 2000L, 10000L, null, LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(5002L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(15L)).thenReturn(Optional.of(catTpl));

        CalculateRequest digReq = new CalculateRequest(USER,
                List.of(new CalculateRequest.OrderItem("SKU1", "CAT_DIGITAL", 15000L, 1)), List.of(5002L));
        CalculateResponse r = service.calculate(digReq, NOW);

        assertThat(r.discountAmount()).as("品类不匹配 → 券不可用").isEqualTo(0L);
        assertThat(r.usedCoupons()).isEmpty();
        assertThat(r.unusableCoupons()).hasSize(1);
        assertThat(r.unusableCoupons().get(0).reason()).contains("适用范围");
    }

    @Test
    @DisplayName("T15: 行级分摊——两行按比例分摊，合计 = 总优惠（资损防线）")
    void t15_allocationSumsToTotal() {
        CouponTemplate tpl = tpl(1L, CouponType.FULL_REDUCTION, 2000L, null, 10000L, "满100减20");
        UserCoupon c = coupon(6001L, 1L, 2000L, 10000L, null, LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(6001L, USER)).thenReturn(Optional.of(c));
        when(templateReader.get(1L)).thenReturn(Optional.of(tpl));

        // A=6000分(60元) + B=4000分(40元) = 10000分(100元)；满100减20 → 优惠2000分
        // A 分摊 = 6000*2000/10000 = 1200；B 分摊 = 2000-1200 = 800（余数）
        CalculateRequest twoLineReq = new CalculateRequest(USER,
                List.of(
                    new CalculateRequest.OrderItem("SKU_A", "CAT1", 6000L, 1),
                    new CalculateRequest.OrderItem("SKU_B", "CAT1", 4000L, 1)),
                List.of(6001L));
        CalculateResponse r = service.calculate(twoLineReq, NOW);

        assertThat(r.discountAmount()).isEqualTo(2000L);
        assertThat(r.allocations()).hasSize(2);
        assertThat(r.allocations().get(0).skuId()).isEqualTo("SKU_A");
        assertThat(r.allocations().get(0).allocatedDiscount()).as("A 行分摊 1200").isEqualTo(1200L);
        assertThat(r.allocations().get(1).skuId()).isEqualTo("SKU_B");
        assertThat(r.allocations().get(1).allocatedDiscount()).as("B 行分摊 800（余数）").isEqualTo(800L);
        long sum = r.allocations().stream().mapToLong(CalculateResponse.LineAllocation::allocatedDiscount).sum();
        assertThat(sum).as("分摊合计 = 总优惠").isEqualTo(r.discountAmount());
    }

    @Test
    @DisplayName("T16: 总优惠封顶——两层叠加优惠不超过订单金额")
    void t16_totalDiscountCapped() {
        CouponTemplate shopTpl = tplLayer(16L, CouponType.FULL_REDUCTION, 5000L, null, 1000L, "满10减50", StackingLayer.SHOP);
        CouponTemplate platTpl = tplLayer(17L, CouponType.NO_THRESHOLD, 2000L, null, 0L, "立减20", StackingLayer.PLATFORM);
        UserCoupon shopC = coupon(7001L, 16L, 5000L, 1000L, null, LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        UserCoupon platC = coupon(7002L, 17L, 2000L, 0L, null, LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        when(userCouponRepository.findByIdForUser(7001L, USER)).thenReturn(Optional.of(shopC));
        when(userCouponRepository.findByIdForUser(7002L, USER)).thenReturn(Optional.of(platC));
        when(templateReader.get(16L)).thenReturn(Optional.of(shopTpl));
        when(templateReader.get(17L)).thenReturn(Optional.of(platTpl));

        // 订单 3000 分；SHOP 优惠 min(5000,3000)=3000；PLATFORM 优惠 min(2000,3000)=2000
        // 总优惠 = min(3000+2000, 3000) = 3000（封顶）
        CalculateResponse r = service.calculate(req(List.of(item(3000L)), List.of(7001L, 7002L)), NOW);

        assertThat(r.discountAmount()).as("总优惠封顶到订单金额").isEqualTo(3000L);
        assertThat(r.finalPrice()).as("到手价 = 0").isEqualTo(0L);
    }
}
