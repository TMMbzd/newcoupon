package com.newcoupon.application.service.discount;

import com.newcoupon.application.service.AppOwnership;
import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.application.service.discount.checker.EligibilityChecker;
import com.newcoupon.application.service.discount.strategy.DiscountStrategy;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.domain.coupon.ApplicableScope;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.StackingLayer;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.open.api.dto.CalculateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 优惠计算引擎主服务（第 2 环升级：两层叠加 + 行级分摊）。
 *
 * <p>第 2 环新增三项能力：
 * <ul>
 *   <li><b>两层叠加</b>：店铺级券（SHOP）与平台级券（PLATFORM）跨层可叠加（各减各的），
 *       同层互斥（选优惠金额最大的）；总优惠不超过订单金额。</li>
 *   <li><b>范围校验</b>（五维第二维）：全场券不限商品；品类/单品券要求订单中至少一件商品匹配范围。</li>
 *   <li><b>行级分摊</b>：总优惠按各商品行金额比例分摊到行，前 n-1 行整除取整、末行取余数
 *       （保证分摊合计 = 总优惠，资损防线）；退款按分摊行反向冲回。</li>
 * </ul>
 *
 * <p>计价顺序（对标有赞/抖音/淘宝官方规则）：同层互斥、跨层叠加。
 * 骨架按八层位次建模（单品级→限时折扣→满减→商家券→平台券→积分→运费→收尾），
 * 本期实装两层（SHOP=第 4 层 / PLATFORM=第 5 层），其余层留接口写明点亮条件。
 *
 * <p>注意：试算是只读操作（不改状态、不动钱、频率极高），不挂 @AuditLog——
 * 审计只给"动钱"的写操作用（核销/发布模板等），高频只读操作靠 open_api_audit 访问日志覆盖。
 */
@Service
public class DiscountCalculatorService {

    private static final Logger log = LoggerFactory.getLogger(DiscountCalculatorService.class);

    private final List<EligibilityChecker> checkers;
    private final Map<String, DiscountStrategy> strategies;
    private final UserCouponRepository userCouponRepository;
    private final CachedTemplateReader templateReader;

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
            // ① 查券
            Optional<UserCoupon> found = userCouponRepository.findByIdForUser(couponId, request.userId());
            if (found.isEmpty()) {
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId, "券不存在或不可见"));
                continue;
            }
            UserCoupon coupon = found.get();

            // ② 归属校验——对外不可区分 + 对内留痕（详见环 0 讲解）
            String callerAppId = AppOwnership.currentAppId();
            if (callerAppId != null && !callerAppId.equals(coupon.getAppId())) {
                log.warn("OWNERSHIP_VIOLATION appId={} 探测非本租户券 couponId={} userId={}",
                        callerAppId, couponId, request.userId());
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId, "券不存在或不可见"));
                continue;
            }

            // ③ 查模板（拿类型 + 范围 + 层级）
            Optional<CouponTemplate> templateOpt = templateReader.get(coupon.getTemplateId());
            if (templateOpt.isEmpty()) {
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId, "模板不存在或已删除"));
                continue;
            }
            CouponTemplate template = templateOpt.get();

            // ④ 责任链校验（时间→状态→门槛）
            String failureReason = checkEligibility(coupon, context);
            if (failureReason != null) {
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId, failureReason));
                continue;
            }

            // ④.5 范围校验（五维第二维：applicable_scope）
            if (!isScopeMatched(template, context)) {
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId,
                        "商品不在券的适用范围内（" + template.getApplicableScope().getDesc() + "）"));
                continue;
            }

            // ⑤ 按类型选策略算钱
            DiscountStrategy strategy = strategies.get(template.getCouponType().name());
            if (strategy == null) {
                throw new IllegalStateException("未注册的券类型策略：" + template.getCouponType());
            }
            long discountAmount = strategy.calculate(coupon, context);

            if (discountAmount > 0) {
                eligibleCoupons.add(new CouponWithDiscount(coupon, template, discountAmount));
            } else {
                unusableCoupons.add(new CalculateResponse.CouponReason(couponId, "订单金额为 0，无优惠可用"));
            }
        }

        // ════ 两层择优（第 2 环新增）════
        // 跨层可叠加（SHOP + PLATFORM 各选一张），同层互斥（选优惠金额最大的）
        CouponWithDiscount bestShop = pickBestByLayer(eligibleCoupons, StackingLayer.SHOP);
        CouponWithDiscount bestPlatform = pickBestByLayer(eligibleCoupons, StackingLayer.PLATFORM);

        long orderAmount = context.getOrderAmount();
        long shopDiscount = bestShop != null ? bestShop.discountAmount() : 0;
        long platformDiscount = bestPlatform != null ? bestPlatform.discountAmount() : 0;
        long totalDiscount = Math.min(shopDiscount + platformDiscount, orderAmount);

        // ════ 行级分摊（第 2 环新增）════
        List<CouponWithDiscount> usedCoupons = new ArrayList<>();
        if (bestShop != null) {
            usedCoupons.add(bestShop);
        }
        if (bestPlatform != null) {
            usedCoupons.add(bestPlatform);
        }
        List<CalculateResponse.LineAllocation> allocations =
                usedCoupons.isEmpty() ? List.of() : allocateToLines(context.getItems(), usedCoupons, orderAmount);

        // ════ 构建响应 ════
        List<CalculateResponse.CouponUsage> usageList = usedCoupons.stream()
                .map(cwd -> new CalculateResponse.CouponUsage(
                        cwd.coupon().getId(),
                        cwd.template().getName(),
                        cwd.template().getStackingLayer().name(),
                        cwd.discountAmount()))
                .toList();

        long finalPrice = orderAmount - totalDiscount;
        return new CalculateResponse(orderAmount, totalDiscount, finalPrice,
                usageList, allocations, unusableCoupons);
    }

    // ════ 核销算钱（第 2 环新增）════

    /** 核销计算结果：优惠金额 + 模板（调用方用模板的 budget_owner 做出资拆分） */
    public record ConfirmCalculation(long discountAmt, CouponTemplate template) {}

    /**
     * 为核销计算单张券的优惠金额。
     * 与试算引擎的区别：不走两层择优（核销时已锁定具体哪张券），不做资格校验
     * （券已锁定=资格已通过；过期等守卫由 TradeTxService 的 CAS 谓词兜底）。
     * 使用与试算完全相同的策略 Bean，保证"试算说多少、核销就算多少"。
     */
    public ConfirmCalculation calculateForConfirm(UserCoupon coupon,
            List<CalculateRequest.OrderItem> items) {
        long orderAmount = items.stream().mapToLong(i -> i.price() * i.quantity()).sum();
        List<OrderContext.OrderItem> orderItems = items.stream()
                .map(i -> new OrderContext.OrderItem(i.skuId(), i.categoryId(), i.price(), i.quantity()))
                .toList();
        OrderContext context = OrderContext.builder()
                .userId(coupon.getUserId())
                .orderAmount(orderAmount)
                .items(orderItems)
                .now(LocalDateTime.now())
                .build();

        CouponTemplate template = templateReader.get(coupon.getTemplateId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "模板不存在或已删除: " + coupon.getTemplateId()));

        DiscountStrategy strategy = strategies.get(template.getCouponType().name());
        if (strategy == null) {
            throw new IllegalStateException("未注册的券类型策略：" + template.getCouponType());
        }
        return new ConfirmCalculation(strategy.calculate(coupon, context), template);
    }

    // ════ 私有方法 ════

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

    private String checkEligibility(UserCoupon coupon, OrderContext context) {
        for (EligibilityChecker checker : checkers) {
            Result<Void> result = checker.check(coupon, context);
            if (result.getCode() != ErrorCode.SUCCESS.getCode()) {
                return result.getMessage();
            }
        }
        return null;
    }

    /**
     * 范围校验（五维第二维）——券的 applicable_scope 与订单商品的匹配判断。
     * 规则：ALL 全通过；CATEGORY/SKU 要求订单中至少一件商品匹配（不是"全部商品都要匹配"——
     * 食品类券在"食品+饮料"的购物车里也能用，只是门槛和分摊基于全量商品）。
     */
    private boolean isScopeMatched(CouponTemplate template, OrderContext context) {
        ApplicableScope scope = template.getApplicableScope();
        if (scope == ApplicableScope.ALL) {
            return true;
        }
        String scopeIds = template.getScopeIds();
        if (scopeIds == null || scopeIds.isBlank()) {
            return true; // 范围值为空 = 不限制
        }
        // 解析 JSON 数组 ["1001","1002"] → Set<String>
        String cleaned = scopeIds.replaceAll("[\\[\\]\"]", "").trim();
        if (cleaned.isEmpty()) {
            return true;
        }
        Set<String> allowedIds = Set.of(cleaned.split(",\\s*"));

        return context.getItems().stream().anyMatch(item -> {
            String itemValue = scope == ApplicableScope.CATEGORY
                    ? item.getCategoryId()
                    : item.getSkuId();
            return allowedIds.contains(itemValue);
        });
    }

    /** 同层择优：优惠金额降序、同额取券 ID 小者（结果确定，测试可断言） */
    private CouponWithDiscount pickBestByLayer(List<CouponWithDiscount> coupons, StackingLayer layer) {
        return coupons.stream()
                .filter(cwd -> cwd.template().getStackingLayer() == layer)
                .sorted(Comparator.comparingLong(CouponWithDiscount::discountAmount).reversed()
                        .thenComparing(cwd -> cwd.coupon().getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 行级分摊：每张券的优惠按行金额比例分摊到各商品行。
     * 前 n-1 行整除取整（floor），末行 = 券优惠 - 前面之和（保证合计精确）。
     * 分摊合计 = 所有券优惠之和 = 总优惠（资损防线：断言在测试中验证）。
     */
    private List<CalculateResponse.LineAllocation> allocateToLines(
            List<OrderContext.OrderItem> items,
            List<CouponWithDiscount> coupons,
            long orderAmount) {
        if (orderAmount <= 0 || coupons.isEmpty() || items.isEmpty()) {
            return List.of();
        }

        long[] lineTotals = new long[items.size()];
        for (CouponWithDiscount cwd : coupons) {
            long discount = cwd.discountAmount();
            long allocatedSoFar = 0;
            for (int i = 0; i < items.size() - 1; i++) {
                long lineAmount = items.get(i).getPrice() * items.get(i).getQuantity();
                long share = lineAmount * discount / orderAmount;  // 整除取整
                lineTotals[i] += share;
                allocatedSoFar += share;
            }
            // 末行取余数（保证该券的分摊合计 = 该券的优惠金额）
            lineTotals[items.size() - 1] += discount - allocatedSoFar;
        }

        List<CalculateResponse.LineAllocation> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            long lineAmount = items.get(i).getPrice() * items.get(i).getQuantity();
            result.add(new CalculateResponse.LineAllocation(
                    items.get(i).getSkuId(), lineAmount, lineTotals[i]));
        }
        return result;
    }

    /** 中间结果：券 + 模板（含层级/范围信息）+ 优惠金额 */
    private record CouponWithDiscount(UserCoupon coupon, CouponTemplate template, long discountAmount) {
    }
}
