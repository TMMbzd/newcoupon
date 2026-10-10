package com.newcoupon.application.service.discount;

import com.newcoupon.application.service.AppOwnership;
import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.application.service.discount.checker.EligibilityChecker;
import com.newcoupon.application.service.discount.strategy.DiscountStrategy;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.open.api.dto.CalculateResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 优惠计算引擎主服务：协调责任链校验和策略计算，返回最优优惠方案。
 *
 * <p>职责：
 * <ul>
 *   <li>接收订单商品 + 用户选中的券（身份来自开放面签名，不在请求体里重复携带）；</li>
 *   <li>逐券解析：归属校验（越权防线）→ 模板解析（券类型在模板上）→ 责任链校验 → 策略计算；</li>
 *   <li>择优：返回优惠金额最大的单张券（优惠金额相同取券 ID 较小者——结果确定，测试可断言）；</li>
 *   <li>构建响应：原价、优惠金额、最终价格、使用的券、不可用的券及原因（每张券都有裁决）。</li>
 * </ul>
 *
 * <p>关键设计决策（2026-10-10 返工裁定，对应 02-现状审查 §2 的 B2/B4/M4）：
 * <ul>
 *   <li><b>券类型从模板取，不在快照上另造列</b>（B2）：券模板核心字段创建后不可改、发布后锁定，
 *       模板即事实源；用户券快照只有面额/门槛/封顶三列（领取时固化），够策略计算用。
 *       读取走 {@link CachedTemplateReader}（本地缓存+负缓存，读路径安全）。</li>
 *   <li><b>每张券先过归属校验</b>（B4）：开放面请求带 appId（来自签名认证），券行上的归属列
 *       与之不符即整单拒绝（RESOURCE_FORBIDDEN）——对齐 TradeAppService 的既有防线写法；
 *       越权是错误不是"不可用原因"，不该静默降级进 unusableCoupons。</li>
 *   <li><b>时间可注入</b>（M4）：{@code calculate(request)} 委托给 {@code calculate(request, now)}，
 *       测试传固定时间——"测试里写死日期"是定时炸弹（过几天全量假红）。</li>
 * </ul>
 *
 * <p>责任链顺序契约：Checker 按 @Order 定序——时间(100) → 状态(200) → 门槛(300)。
 * 顺序决定"用户看到的第一个失败原因"：先说时间问题、再说状态、最后说门槛。
 * 改顺序=改对外文案契约，须同步改测试。
 *
 * <p>Phase 1 边界（第 2 步将扩展）：单券择优（不做叠加组合）；适用范围暂不校验（全部按全场）；
 * 计算不锁券（锁券核销在交易侧）。
 */
@Service
public class DiscountCalculatorService {

    private final List<EligibilityChecker> checkers;
    private final Map<String, DiscountStrategy> strategies;
    private final UserCouponRepository userCouponRepository;
    private final CachedTemplateReader templateReader;

    /**
     * @param checkers            全部校验器（Spring 按 @Order 排序注入）
     * @param strategies          全部策略（key = Bean 名称 = {@link com.newcoupon.domain.coupon.CouponType} 枚举名）
     * @param userCouponRepository 用户券仓储
     * @param templateReader      模板读取（带本地缓存；模板核心字段发布后不可变，缓存读安全）
     */
    public DiscountCalculatorService(
            List<EligibilityChecker> checkers,
            Map<String, DiscountStrategy> strategies,
            UserCouponRepository userCouponRepository,
            CachedTemplateReader templateReader) {
        this.checkers = checkers;
        this.strategies = strategies;
        this.userCouponRepository = userCouponRepository;
        this.templateReader = templateReader;
    }

    /** 生产入口：用系统当前时间。 */
    public CalculateResponse calculate(CalculateRequest request) {
        return calculate(request, LocalDateTime.now());
    }

    /** 测试可注入固定时间的入口（包内可见）。 */
    CalculateResponse calculate(CalculateRequest request, LocalDateTime now) {
        OrderContext context = buildOrderContext(request, now);

        List<CouponWithDiscount> eligibleCoupons = new ArrayList<>();
        List<CalculateResponse.CouponReason> unusableCoupons = new ArrayList<>();

        for (Long couponId : request.couponIds()) {
            // ① 查券（带分片键 userId，单表命中）；查不到也要给裁决——"每张券都有说法"，不静默丢弃
            Optional<UserCoupon> found = userCouponRepository.findByIdForUser(couponId, request.userId());
            if (found.isEmpty()) {
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId, "券不存在或不可见"));
                continue;
            }
            UserCoupon coupon = found.get();

            // ② 归属校验（越权防线）：开放面不匹配即整单拒绝；内部面（无 appId，平台自己）放行
            AppOwnership.assertAccessible(coupon.getAppId(), "coupon:" + couponId);

            // ③ 模板解析：券类型在模板上（快照没有类型列）；模板没了按不可用处理（防御）
            Optional<CouponTemplate> templateOpt = templateReader.get(coupon.getTemplateId());
            if (templateOpt.isEmpty()) {
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId, "模板不存在或已删除"));
                continue;
            }
            CouponTemplate template = templateOpt.get();

            // ④ 责任链校验：时间 → 状态 → 门槛（@Order 定序）
            String failureReason = checkEligibility(coupon, context);
            if (failureReason != null) {
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId, failureReason));
                continue;
            }

            // ⑤ 策略计算：按模板券类型选策略（满减/满折/无门槛）
            DiscountStrategy strategy = strategies.get(template.getCouponType().name());
            if (strategy == null) {
                // 三种类型都有策略后此分支理论不可达——保留显式失败，防"新增类型忘了写策略"静默漏算
                throw new IllegalStateException("未注册的券类型策略：" + template.getCouponType());
            }
            long discountAmount = strategy.calculate(coupon, context);

            if (discountAmount > 0) {
                eligibleCoupons.add(new CouponWithDiscount(coupon, template.getName(), discountAmount));
            } else {
                // 走到这里说明校验全过但金额算出 0——只有订单金额为 0 一种可能
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId, "订单金额为 0，无优惠可用"));
            }
        }

        // ⑥ 择优：优惠金额降序、同额取券 ID 升序（结果确定）
        Optional<CouponWithDiscount> bestCoupon = eligibleCoupons.stream()
                .sorted(Comparator.comparingLong(CouponWithDiscount::discountAmount).reversed()
                        .thenComparing(c -> c.coupon().getId()))
                .findFirst();

        return buildResponse(context, bestCoupon, unusableCoupons);
    }

    /** 构建订单上下文：明细累加为订单金额，时间用入参（可注入）。 */
    private OrderContext buildOrderContext(CalculateRequest request, LocalDateTime now) {
        long orderAmount = request.items().stream()
                .mapToLong(item -> item.price() * item.quantity())
                .sum();

        List<OrderContext.OrderItem> orderItems = request.items().stream()
                .map(item -> new OrderContext.OrderItem(
                        item.skuId(), item.categoryId(), item.price(), item.quantity()))
                .toList();

        return OrderContext.builder()
                .userId(request.userId())
                .orderAmount(orderAmount)
                .items(orderItems)
                .now(now)
                .build();
    }

    /** 责任链：任一校验失败即短路，返回失败原因文案；全部通过返回 null。 */
    private String checkEligibility(UserCoupon coupon, OrderContext context) {
        for (EligibilityChecker checker : checkers) {
            Result<Void> result = checker.check(coupon, context);
            if (result.getCode() != ErrorCode.SUCCESS.getCode()) {
                return result.getMessage();
            }
        }
        return null;
    }

    private CalculateResponse buildResponse(
            OrderContext context,
            Optional<CouponWithDiscount> bestCoupon,
            List<CalculateResponse.CouponReason> unusableCoupons) {

        long originalPrice = context.getOrderAmount();
        long discountAmount = bestCoupon.map(CouponWithDiscount::discountAmount).orElse(0L);
        long finalPrice = originalPrice - discountAmount;

        List<CalculateResponse.CouponUsage> usedCoupons = bestCoupon
                .map(c -> List.of(new CalculateResponse.CouponUsage(
                        c.coupon().getId(),
                        c.templateName(),
                        c.discountAmount())))
                .orElse(List.of());

        return new CalculateResponse(originalPrice, discountAmount, finalPrice, usedCoupons, unusableCoupons);
    }

    /** 中间结果：券 + 模板名 + 优惠金额（模板名来自模板而非 templateCode——给人看的字段要可读）。 */
    private record CouponWithDiscount(UserCoupon coupon, String templateName, long discountAmount) {
    }
}
