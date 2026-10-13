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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 优惠计算引擎（Phase 1 完整版：互斥组 + 递进门槛 + 范围感知分摊）。
 *
 * 三项完整版能力（此前是轻量版）：
 * ① 互斥组：同层内同组互斥（选最优一张），不同组可叠加——不再是"每层只选一张"
 * ② 递进门槛：PLATFORM 层门槛按 SHOP 层折后价判断——不再是"都按原价判断"
 * ③ 范围感知分摊：每张券的优惠只分给它范围内商品——不再是"全部按总额分摊"
 */
@Service
public class DiscountCalculatorService {

    private static final Logger log = LoggerFactory.getLogger(DiscountCalculatorService.class);

    /** 层级处理顺序：SHOP 先算（门槛按原价），PLATFORM 后算（门槛按折后价） */
    private static final List<StackingLayer> LAYER_ORDER = List.of(StackingLayer.SHOP, StackingLayer.PLATFORM);

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

    public CalculateResponse calculate(CalculateRequest request) {
        return calculate(request, LocalDateTime.now());
    }

    CalculateResponse calculate(CalculateRequest request, LocalDateTime now) {
        OrderContext context = buildOrderContext(request, now);
        long orderAmount = context.getOrderAmount();

        List<CandidateCoupon> candidates = new ArrayList<>();
        List<CalculateResponse.CouponReason> unusable = new ArrayList<>();

        // ════ Phase 1：逐张独立检查（时间/状态/归属/范围）——不含门槛 ════
        for (Long couponId : request.couponIds()) {
            CandidateCoupon cc = checkCoupon(couponId, request.userId(), context, unusable);
            if (cc != null) {
                candidates.add(cc);
            }
        }

        // ════ Phase 2：按层顺序处理（SHOP→PLATFORM），含递进门槛 + 互斥组 ════
        long remaining = orderAmount;
        List<CandidateCoupon> usedCoupons = new ArrayList<>();

        for (StackingLayer layer : LAYER_ORDER) {
            List<CandidateCoupon> layerCandidates = candidates.stream()
                    .filter(cc -> cc.template().getStackingLayer() == layer)
                    .toList();
            if (layerCandidates.isEmpty()) {
                continue;
            }

            List<CandidateCoupon> passing = new ArrayList<>();

            for (CandidateCoupon cc : layerCandidates) {
                long threshold = cc.coupon().getThreshold();
                // 门槛基准：本层第一张时按原价，本层后续或下一层时按递进后的剩余额
                // 简化实现：整个层的门槛都按 currentAmount 检查（层内所有券共知同一基准）
                if (remaining < threshold) {
                    unusable.add(new CalculateResponse.CouponReason(
                            cc.coupon().getId(),
                            String.format("订单金额未达使用门槛（需满%.2f元，当前%.2f元）",
                                    threshold / 100.0, remaining / 100.0)));
                    continue;
                }

                DiscountStrategy strategy = strategies.get(cc.template().getCouponType().name());
                if (strategy == null) {
                    throw new IllegalStateException("未注册的券类型策略：" + cc.template().getCouponType());
                }
                long discount = strategy.calculate(cc.coupon(), context);
                if (discount > 0) {
                    passing.add(new CandidateCoupon(cc.coupon(), cc.template(), discount, cc.scopeItems()));
                } else {
                    unusable.add(new CalculateResponse.CouponReason(cc.coupon().getId(), "订单金额为 0，无优惠可用"));
                }
            }

            // 互斥组择优：同组选最优一张，不同组可叠加
            List<CandidateCoupon> winners = selectByExclusionGroups(passing, layer);
            usedCoupons.addAll(winners);

            long layerDiscount = winners.stream().mapToLong(CandidateCoupon::discount).sum();
            remaining -= layerDiscount;
        }

        // ════ Phase 3：总优惠封顶 ════
        long totalDiscount = Math.min(
                usedCoupons.stream().mapToLong(CandidateCoupon::discount).sum(),
                orderAmount);

        // ════ Phase 4：范围感知分摊（每张券只分给它范围内的商品）════
        List<CalculateResponse.LineAllocation> allocations =
                usedCoupons.isEmpty() ? List.of() : allocateWithScopes(context.getItems(), usedCoupons);

        // ════ 构建响应 ════
        List<CalculateResponse.CouponUsage> usageList = usedCoupons.stream()
                .map(cc -> new CalculateResponse.CouponUsage(
                        cc.coupon().getId(),
                        cc.template().getName(),
                        cc.template().getStackingLayer().name(),
                        cc.discount()))
                .toList();

        long finalPrice = orderAmount - totalDiscount;
        return new CalculateResponse(orderAmount, totalDiscount, finalPrice,
                usageList, allocations, unusable);
    }

    // ════ 核销算钱 ════

    public record ConfirmCalculation(long discountAmt, CouponTemplate template) {}

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
                .orElseThrow(() -> new IllegalArgumentException("模板不存在或已删除: " + coupon.getTemplateId()));
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

    /** 单张券的独立检查（查券→归属→模板→时间/状态→范围），不含门槛 */
    private CandidateCoupon checkCoupon(Long couponId, Long userId,
                                        OrderContext context,
                                        List<CalculateResponse.CouponReason> unusable) {
        // 查券
        var found = userCouponRepository.findByIdForUser(couponId, userId);
        if (found.isEmpty()) {
            unusable.add(new CalculateResponse.CouponReason(couponId, "券不存在或不可见"));
            return null;
        }
        UserCoupon coupon = found.get();

        // 归属校验
        String callerAppId = AppOwnership.currentAppId();
        if (callerAppId != null && !callerAppId.equals(coupon.getAppId())) {
            log.warn("OWNERSHIP_VIOLATION appId={} 探测非本租户券 couponId={}", callerAppId, couponId);
            unusable.add(new CalculateResponse.CouponReason(couponId, "券不存在或不可见"));
            return null;
        }

        // 查模板
        var templateOpt = templateReader.get(coupon.getTemplateId());
        if (templateOpt.isEmpty()) {
            unusable.add(new CalculateResponse.CouponReason(couponId, "模板不存在或已删除"));
            return null;
        }
        CouponTemplate template = templateOpt.get();

        // 时间/状态校验（不含门槛——门槛在层处理时检查）
        for (EligibilityChecker checker : checkers) {
            Result<Void> result = checker.check(coupon, context);
            if (result.getCode() != ErrorCode.SUCCESS.getCode()) {
                // 门槛校验器的结果在递进模式下不在此处理（跳过）
                // 但当前 checker 链里 ThresholdChecker 也在里面，需要区分
                // 简化方案：这里只做时间+状态，门槛独立检查
                if (result.getMessage().contains("门槛")) {
                    continue; // 门槛留到层处理时判
                }
                unusable.add(new CalculateResponse.CouponReason(couponId, result.getMessage()));
                return null;
            }
        }

        // 范围校验 + 收集范围内的商品行
        List<OrderContext.OrderItem> scopeItems = filterScopeItems(template, context);
        if (template.getApplicableScope() != ApplicableScope.ALL && scopeItems.isEmpty()) {
            unusable.add(new CalculateResponse.CouponReason(couponId,
                    "商品不在券的适用范围内（" + template.getApplicableScope().getDesc() + "）"));
            return null;
        }

        return new CandidateCoupon(coupon, template, 0, scopeItems);
    }

    /** 互斥组择优：同层内，同组选最优一张，不同组（含无组）可叠加 */
    private List<CandidateCoupon> selectByExclusionGroups(
            List<CandidateCoupon> passing, StackingLayer layer) {
        Map<String, List<CandidateCoupon>> byGroup = new HashMap<>();
        for (CandidateCoupon cc : passing) {
            String group = cc.template().getExclusionGroup();
            String key = (group == null || group.isBlank())
                    ? "__default__"  // 无组 = 默认互斥（同层无组互斥，业界标准）
                    : group;
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(cc);
        }

        List<CandidateCoupon> winners = new ArrayList<>();
        for (List<CandidateCoupon> group : byGroup.values()) {
            if (group.size() == 1) {
                winners.add(group.get(0));
            } else {
                // 同组选优惠最大的；同额取 ID 小的
                winners.add(group.stream()
                        .sorted(Comparator.comparingLong(CandidateCoupon::discount).reversed()
                                .thenComparing(cc -> cc.coupon().getId()))
                        .findFirst().orElseThrow());
            }
        }
        return winners;
    }

    /** 范围感知分摊：每张券的优惠只分给它范围内的商品 */
    private List<CalculateResponse.LineAllocation> allocateWithScopes(
            List<OrderContext.OrderItem> allItems, List<CandidateCoupon> usedCoupons) {
        if (allItems.isEmpty() || usedCoupons.isEmpty()) {
            return List.of();
        }

        long[] totals = new long[allItems.size()];

        for (CandidateCoupon cc : usedCoupons) {
            // 拿这张券的范围商品列表（CandidateCoupon 已在 Phase 1 算好）
            List<OrderContext.OrderItem> scopeItems = cc.scopeItems();
            if (scopeItems == null || scopeItems.isEmpty()) {
                // 范围为 ALL 时 scopeItems = 全部商品
                scopeItems = allItems;
            }

            long scopeTotal = scopeItems.stream()
                    .mapToLong(item -> item.getPrice() * item.getQuantity()).sum();
            if (scopeTotal <= 0) {
                continue;
            }

            long discount = cc.discount();
            long allocatedSoFar = 0;

            // 前 n-1 行按比例取整
            for (int i = 0; i < scopeItems.size() - 1; i++) {
                var item = scopeItems.get(i);
                long lineAmount = item.getPrice() * item.getQuantity();
                long share = lineAmount * discount / scopeTotal;
                allocatedSoFar += share;
                // 找到该商品在 allItems 中的索引并累加
                int idx = indexOfItem(allItems, item);
                if (idx >= 0) {
                    totals[idx] += share;
                }
            }

            // 末行取余数
            var lastItem = scopeItems.get(scopeItems.size()-1);
            long lastShare = discount - allocatedSoFar;
            int lastIdx = indexOfItem(allItems, lastItem);
            if (lastIdx >= 0) {
                totals[lastIdx] += lastShare;
            }
        }

        List<CalculateResponse.LineAllocation> result = new ArrayList<>();
        for (int i = 0; i < allItems.size(); i++) {
            long lineAmount = allItems.get(i).getPrice() * allItems.get(i).getQuantity();
            result.add(new CalculateResponse.LineAllocation(
                    allItems.get(i).getSkuId(), lineAmount, totals[i]));
        }
        return result;
    }

    /** 过滤出模板范围内的商品行 */
    private List<OrderContext.OrderItem> filterScopeItems(
            CouponTemplate template, OrderContext context) {
        ApplicableScope scope = template.getApplicableScope();
        if (scope == ApplicableScope.ALL) {
            return context.getItems();
        }
        String scopeIds = template.getScopeIds();
        if (scopeIds == null || scopeIds.isBlank()) {
            return context.getItems();
        }
        String cleaned = scopeIds.replaceAll("[\\[\\]\"]", "").trim();
        if (cleaned.isEmpty()) {
            return context.getItems();
        }
        Set<String> allowed = Set.of(cleaned.split(",\\s*"));

        return context.getItems().stream()
                .filter(item -> {
                    String value = scope == ApplicableScope.CATEGORY
                            ? item.getCategoryId() : item.getSkuId();
                    return allowed.contains(value);
                })
                .toList();
    }

    private int indexOfItem(List<OrderContext.OrderItem> items, OrderContext.OrderItem target) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getSkuId().equals(target.getSkuId())) {
                return i;
            }
        }
        return -1;
    }

    /** 中间结果：券 + 模板 + 优惠金额 + 范围内商品 */
    record CandidateCoupon(UserCoupon coupon, CouponTemplate template, long discount,
                           List<OrderContext.OrderItem> scopeItems) {}
}
