package com.newcoupon.application.service.discount;

import com.newcoupon.application.service.AppOwnership;
import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.application.service.discount.checker.EligibilityChecker;
import com.newcoupon.application.service.discount.strategy.DiscountStrategy;
import com.newcoupon.common.BizException;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 优惠计算引擎（2026-10-18 行业标准版：范围金额口径 + 行号 + 逐行逐券分摊）。
 *
 * <p>相对旧版的四处变化：
 * <ol>
 *   <li><b>门槛/折扣基数按“券适用范围内、进入本层前的有效金额”</b>——品类券不再按整单金额算；
 *       全场券的范围就是整单。递进门槛（SHOP 先减、PLATFORM 后减）自然落到“本层前的行剩余金额”上。</li>
 *   <li><b>每一层优惠按“进入本层前的行剩余金额”比例分摊</b>——不是所有层都按原始行金额分摊；
 *       与行业“逐层扣减、逐层分摊”一致。</li>
 *   <li><b>单行不超过该行剩余金额</b>——前 n-1 行向下取整、末行兜底；末行溢出（最多 n-1 分）
 *       再补给还有余量的行，保证“行分摊合计 = 实际优惠”。</li>
 *   <li><b>行号与逐券明细</b>——同一 SKU 多行不再按 skuId 合并；响应里每行带 lineNo 和
 *       每张券各分了多少（退款按行 × 券冲回）。</li>
 * </ol>
 */
@Service
public class DiscountCalculatorService {

    private static final Logger log = LoggerFactory.getLogger(DiscountCalculatorService.class);

    /** 层级处理顺序：SHOP 先算（门槛按原价），PLATFORM 后算（门槛按折后剩余额） */
    private static final List<StackingLayer> LAYER_ORDER = List.of(StackingLayer.SHOP, StackingLayer.PLATFORM);

    private final List<EligibilityChecker> checkers;
    private final Map<String, DiscountStrategy> strategies;
    private final UserCouponRepository userCouponRepository;
    private final CachedTemplateReader templateReader;
    private final com.newcoupon.application.service.SettlePartyPolicy settlePartyPolicy;
    /** 旧商家/共担模板缺主体：每个模板每 JVM 只告警一次，避免热路径刷日志 */
    private final Set<Long> legacyPartyWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DiscountCalculatorService(
            List<EligibilityChecker> checkers,
            Map<String, DiscountStrategy> strategies,
            UserCouponRepository userCouponRepository,
            CachedTemplateReader templateReader,
            com.newcoupon.application.service.SettlePartyPolicy settlePartyPolicy) {
        this.checkers = checkers;
        this.strategies = strategies;
        this.userCouponRepository = userCouponRepository;
        this.templateReader = templateReader;
        this.settlePartyPolicy = settlePartyPolicy;
    }

    public CalculateResponse calculate(CalculateRequest request) {
        return calculateWithDetails(request, settlePartyPolicy.isEnabled(), LocalDateTime.now())
                .response();
    }

    CalculateResponse calculate(CalculateRequest request, LocalDateTime now) {
        return calculateWithDetails(request, settlePartyPolicy.isEnabled(), now).response();
    }

    /**
     * 带详细结果的试算（环 2）：除响应外还带回标准化行与中选券模板，
     * 供价格快照冻结“为什么是这个价”。老调用方继续用 {@link #calculate(CalculateRequest)}。
     */
    public CalculationOutcome calculateWithDetails(CalculateRequest request, boolean partyEnabled) {
        return calculateWithDetails(request, partyEnabled, LocalDateTime.now());
    }

    public CalculationOutcome calculateWithDetails(CalculateRequest request, boolean partyEnabled,
                                                   LocalDateTime now) {
        List<Line> lines = toLines(request.items(), partyEnabled);
        OrderContext context = toContext(request.userId(), lines, now);

        Map<Long, String> unusable = new LinkedHashMap<>();
        List<CandidateCoupon> candidates = new ArrayList<>();
        for (Long couponId : request.couponIds()) {
            CandidateCoupon cc = checkCoupon(couponId, request.userId(), context, lines,
                    partyEnabled, unusable);
            if (cc != null) {
                candidates.add(cc);
            }
        }

        StackResult stack = runStack(candidates, lines, now, unusable);
        long orderAmount = lines.stream().mapToLong(Line::amount).sum();
        long totalDiscount = stack.totalDiscount();

        List<CalculateResponse.CouponUsage> usageList = stack.applied().stream()
                .map(a -> new CalculateResponse.CouponUsage(
                        a.candidate().coupon().getId(),
                        a.candidate().template().getName(),
                        a.candidate().template().getStackingLayer().name(),
                        a.effectiveDiscount()))
                .toList();

        List<CalculateResponse.LineAllocation> allocations = buildAllocations(lines, stack);

        List<CalculateResponse.CouponReason> unusableList = unusable.entrySet().stream()
                .map(e -> new CalculateResponse.CouponReason(e.getKey(), e.getValue()))
                .toList();

        CalculateResponse response = new CalculateResponse(orderAmount, totalDiscount,
                orderAmount - totalDiscount, usageList, allocations, unusableList);

        Map<Long, CouponTemplate> appliedTemplates = new LinkedHashMap<>();
        for (AppliedCoupon applied : stack.applied()) {
            appliedTemplates.put(applied.candidate().coupon().getId(),
                    applied.candidate().template());
        }
        List<LineView> lineViews = lines.stream()
                .map(l -> new LineView(l.lineNo(), l.skuId(), l.categoryId(), l.price(),
                        l.quantity(), l.amount(), l.settlePartyId()))
                .toList();
        return new CalculationOutcome(response, lineViews, appliedTemplates);
    }

    // ════ 核销算钱 ════

    /** 核销结果：本券实际优惠 + 模板 + 本券在每行上的分摊（核销事务按行落库） */
    public record ConfirmCalculation(long discountAmt, CouponTemplate template,
                                     List<ConfirmLineShare> lineShares) {
        /** 兼容旧调用方/旧测试：只关心总额与模板 */
        public ConfirmCalculation(long discountAmt, CouponTemplate template) {
            this(discountAmt, template, List.of());
        }
    }

    /** 一张券在一行商品上的分摊（退款按行 × 券冲回的最小单位） */
    public record ConfirmLineShare(String lineNo, String skuId, long lineAmount,
                                   int quantity, long discountAmt, String settlePartyId) {
    }

    /**
     * 试算详细结果（环 2 价格快照用）：响应 + 标准化商品行 + 中选券的模板。
     * 快照据此冻结行级分摊与规则摘要；老调用方只看 response。
     */
    public record CalculationOutcome(CalculateResponse response, List<LineView> lines,
                                     Map<Long, CouponTemplate> appliedTemplates) {
    }

    /** 标准化商品行：行号已补齐（L1/L2/...）、主体已按灰度归一 */
    public record LineView(String lineNo, String skuId, String categoryId, long price,
                           int quantity, long lineAmount, String settlePartyId) {
    }

    /**
     * 单券核销复算（兼容入口）：只拿这一张券算。没有订单级上下文时使用。
     */
    public ConfirmCalculation calculateForConfirm(UserCoupon coupon,
            List<CalculateRequest.OrderItem> items) {
        return calculateForOrderConfirm(coupon.getId(), List.of(coupon), items);
    }

    /**
     * 订单级核销复算（L64 修复，2026-10-18）：把同一订单里全部已锁/已核销的券放回同一栈，
     * 按与试算完全相同的“层序 + 互斥组 + 递进门槛 + 范围金额 + 逐行分摊”算法复算，
     * 再取当前券的份额。
     *
     * <p>为什么必须带订单级上下文：试算时平台券的门槛按“店铺券折后的剩余额”判，
     * 单券核销时若按整单原价判，会把试算已拒的券放过去（核销口径比试算松）。
     * 把同订单全部占用单找回来一起复算，两条入口才是同一把尺子。
     *
     * <p>不跑时间/状态检查器：调用方已确认当前占用单处于占用中；兄弟券处于已锁定/已核销，
     * 它们在试算时已经过了时间/状态检查，这里只用它们的规则快照参与叠加。
     */
    public ConfirmCalculation calculateForOrderConfirm(long currentCouponId,
            List<UserCoupon> orderCoupons, List<CalculateRequest.OrderItem> items) {
        boolean partyEnabled = settlePartyPolicy.isEnabled();
        List<Line> lines = toLines(items, partyEnabled);
        Map<Long, String> reasons = new LinkedHashMap<>();
        Set<Long> excluded = new LinkedHashSet<>();

        List<CandidateCoupon> candidates = new ArrayList<>();
        for (UserCoupon coupon : orderCoupons) {
            var templateOpt = templateReader.get(coupon.getTemplateId());
            if (templateOpt.isEmpty()) {
                if (coupon.getId() != null && coupon.getId() == currentCouponId) {
                    throw new IllegalArgumentException("模板不存在或已删除: " + coupon.getTemplateId());
                }
                continue;
            }
            CouponTemplate template = templateOpt.get();
            if (partyEnabled && isMerchantOrSplit(template) && isMissingParty(template.getSettlePartyId())) {
                warnLegacyPartyOnce(template);
                reasons.put(coupon.getId(), "商家/共担券未绑定结算主体（旧模板请迁移）");
                continue;
            }
            int[] scope = scopeIndices(template, lines, partyEnabled);
            if (scope.length == 0) {
                reasons.put(coupon.getId(), scopeReason(template, lines, partyEnabled));
                continue;
            }
            candidates.add(new CandidateCoupon(coupon, template, scope));
        }

        StackResult stack = runStack(candidates, lines, LocalDateTime.now(), reasons);
        AppliedCoupon current = stack.applied().stream()
                .filter(a -> a.candidate().coupon().getId() == currentCouponId)
                .findFirst().orElse(null);
        if (current == null) {
            String reason = reasons.get(currentCouponId);
            if (reason == null && excluded.contains(currentCouponId)) {
                reason = "同订单其他券在本层互斥中更优，当前券不可核销";
            }
            if (reason == null) {
                reason = "同订单其他券叠加后可用额度不足，当前券不可核销";
            }
            throw new BizException(ErrorCode.COUPON_NOT_USABLE, reason);
        }

        List<ConfirmLineShare> shares = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            long discount = current.shares()[i];
            if (discount > 0) {
                Line line = lines.get(i);
                shares.add(new ConfirmLineShare(line.lineNo(), line.skuId(), line.amount(),
                        line.quantity(), discount, line.settlePartyId()));
            }
        }
        return new ConfirmCalculation(current.effectiveDiscount(), current.candidate().template(), shares);
    }

    // ════ 私有方法 ════

    /** 单张券的独立检查（查券→归属→模板→时间/状态→范围/主体），不含门槛（门槛在层处理时按范围金额判） */
    private CandidateCoupon checkCoupon(Long couponId, Long userId, OrderContext context,
                                        List<Line> lines, boolean partyEnabled,
                                        Map<Long, String> unusable) {
        var found = userCouponRepository.findByIdForUser(couponId, userId);
        if (found.isEmpty()) {
            unusable.put(couponId, "券不存在或不可见");
            return null;
        }
        UserCoupon coupon = found.get();

        String callerAppId = AppOwnership.currentAppId();
        if (callerAppId != null && !callerAppId.equals(coupon.getAppId())) {
            log.warn("OWNERSHIP_VIOLATION appId={} 探测非本租户券 couponId={}", callerAppId, couponId);
            unusable.put(couponId, "券不存在或不可见");
            return null;
        }

        var templateOpt = templateReader.get(coupon.getTemplateId());
        if (templateOpt.isEmpty()) {
            unusable.put(couponId, "模板不存在或已删除");
            return null;
        }
        CouponTemplate template = templateOpt.get();
        if (partyEnabled && isMerchantOrSplit(template) && isMissingParty(template.getSettlePartyId())) {
            warnLegacyPartyOnce(template);
            unusable.put(couponId, "商家/共担券未绑定结算主体（旧模板请迁移）");
            return null;
        }

        for (EligibilityChecker checker : checkers) {
            Result<Void> result = checker.check(coupon, context);
            if (result.getCode() != ErrorCode.SUCCESS.getCode()) {
                // 门槛校验器留到层处理时判（它需要“本券范围内的剩余金额”，不是整单金额）
                if (result.getMessage().contains("门槛")) {
                    continue;
                }
                unusable.put(couponId, result.getMessage());
                return null;
            }
        }

        int[] scope = scopeIndices(template, lines, partyEnabled);
        if (scope.length == 0) {
            unusable.put(couponId, scopeReason(template, lines, partyEnabled));
            return null;
        }
        return new CandidateCoupon(coupon, template, scope);
    }

    /**
     * 按层跑完整叠加栈：门槛按“本券范围内、进入本层前的行剩余金额”判；
     * 同层按互斥组择优；中选券按行分摊并扣减行剩余金额；下一层看到的是扣减后的金额。
     */
    private StackResult runStack(List<CandidateCoupon> candidates, List<Line> lines,
                                 LocalDateTime now, Map<Long, String> reasons) {
        long[] remaining = new long[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            remaining[i] = lines.get(i).amount();
        }
        List<AppliedCoupon> applied = new ArrayList<>();
        Set<Long> excluded = new LinkedHashSet<>();

        for (StackingLayer layer : LAYER_ORDER) {
            List<PricedCandidate> priced = new ArrayList<>();
            for (CandidateCoupon cc : candidates) {
                if (cc.template().getStackingLayer() != layer) {
                    continue;
                }
                long scopeBase = sumRemaining(cc.scopeIndices(), remaining);
                if (scopeBase <= 0) {
                    reasons.put(cc.coupon().getId(), "订单金额为 0，无优惠可用");
                    continue;
                }
                long threshold = cc.coupon().getThreshold();
                if (scopeBase < threshold) {
                    reasons.put(cc.coupon().getId(), String.format(
                            "订单金额未达使用门槛（需满%.2f元，当前%.2f元）",
                            threshold / 100.0, scopeBase / 100.0));
                    continue;
                }
                DiscountStrategy strategy = strategies.get(cc.template().getCouponType().name());
                if (strategy == null) {
                    throw new IllegalStateException("未注册的券类型策略：" + cc.template().getCouponType());
                }
                long nominal = strategy.calculate(cc.coupon(),
                        scopedContext(scopeBase, lines, now));
                if (nominal <= 0) {
                    reasons.put(cc.coupon().getId(), "订单金额为 0，无优惠可用");
                    continue;
                }
                priced.add(new PricedCandidate(cc, nominal));
            }

            List<PricedCandidate> winners = selectWinners(priced, excluded);
            // 同层多个互斥组都中选时，按券 ID 升序依次应用——顺序固定，逐行分摊结果才可复现
            winners.sort(Comparator.comparingLong(w -> w.candidate().coupon().getId()));
            for (PricedCandidate winner : winners) {
                long[] shares = allocate(winner.nominalDiscount(), winner.candidate().scopeIndices(), remaining);
                long effective = Arrays.stream(shares).sum();
                if (effective <= 0) {
                    reasons.put(winner.candidate().coupon().getId(), "可用额度不足，未产生优惠");
                    continue;
                }
                for (int i = 0; i < remaining.length; i++) {
                    remaining[i] -= shares[i];
                }
                applied.add(new AppliedCoupon(winner.candidate(), effective, shares));
            }
        }
        return new StackResult(applied, reasons, excluded);
    }

    /** 同层互斥组择优：同组选优惠最大的；同额取 ID 小的；不同组（含无组）可叠加 */
    private List<PricedCandidate> selectWinners(List<PricedCandidate> priced, Set<Long> excluded) {
        Map<String, List<PricedCandidate>> byGroup = new LinkedHashMap<>();
        for (PricedCandidate p : priced) {
            String group = p.candidate().template().getExclusionGroup();
            String key = (group == null || group.isBlank())
                    ? "__default__"   // 无组 = 默认互斥（同层无组互斥，业界标准）
                    : group;
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<PricedCandidate> winners = new ArrayList<>();
        for (List<PricedCandidate> group : byGroup.values()) {
            if (group.size() == 1) {
                winners.add(group.get(0));
                continue;
            }
            group.sort(Comparator.comparingLong(PricedCandidate::nominalDiscount).reversed()
                    .thenComparingLong(p -> p.candidate().coupon().getId()));
            winners.add(group.get(0));
            for (int i = 1; i < group.size(); i++) {
                excluded.add(group.get(i).candidate().coupon().getId());
            }
        }
        return winners;
    }

    /**
     * 把一张券的优惠分到它的范围行：前 n-1 行向下取整、末行兜底；
     * 单行不超过该行剩余金额，末行溢出（最多 n-1 分）补给还有余量的行。
     *
     * <p>例：三行各 1 分、优惠 2 分——前两行 floor(1×2/3)=0、0，末行余数 2 超过其容量 1，
     * 溢出 1 分补到第一行 → 1/0/1，合计 2，且每行不超过 1。
     */
    private long[] allocate(long discount, int[] scopeIndices, long[] remaining) {
        long[] shares = new long[remaining.length];
        if (discount <= 0 || scopeIndices.length == 0) {
            return shares;
        }
        List<Integer> active = new ArrayList<>();
        long scopeTotal = 0;
        for (int idx : scopeIndices) {
            if (remaining[idx] > 0) {
                active.add(idx);
                scopeTotal += remaining[idx];
            }
        }
        if (active.isEmpty()) {
            return shares;
        }

        long capped = Math.min(discount, scopeTotal);
        long allocated = 0;
        for (int k = 0; k < active.size() - 1; k++) {
            int idx = active.get(k);
            long share = capped * remaining[idx] / scopeTotal;
            shares[idx] = share;
            allocated += share;
        }

        int lastIdx = active.get(active.size() - 1);
        long lastShare = capped - allocated;
        if (lastShare <= remaining[lastIdx]) {
            shares[lastIdx] = lastShare;
            return shares;
        }

        // 末行装不下：先装满末行，溢出的几分钱补给前面还有余量的行（溢出最多 n-1 分）
        shares[lastIdx] = remaining[lastIdx];
        long overflow = lastShare - remaining[lastIdx];
        for (int k = 0; k < active.size() - 1 && overflow > 0; k++) {
            int idx = active.get(k);
            long capacity = remaining[idx] - shares[idx];
            long add = Math.min(capacity, overflow);
            shares[idx] += add;
            overflow -= add;
        }
        if (overflow > 0) {
            // 理论上不会到这里（scopeTotal >= capped 保证总容量足够）；到了说明金额计算有误，
            // 宁可少分也不虚增——把多出的部分从末行扣回，保持“分摊合计=实际优惠”守恒。
            shares[lastIdx] -= overflow;
        }
        return shares;
    }

    private List<CalculateResponse.LineAllocation> buildAllocations(List<Line> lines, StackResult stack) {
        List<CalculateResponse.LineAllocation> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            long total = 0;
            List<CalculateResponse.CouponLineShare> couponShares = new ArrayList<>();
            for (AppliedCoupon applied : stack.applied()) {
                long share = applied.shares()[i];
                if (share > 0) {
                    total += share;
                    couponShares.add(new CalculateResponse.CouponLineShare(
                            applied.candidate().coupon().getId(), share));
                }
            }
            Line line = lines.get(i);
            result.add(new CalculateResponse.LineAllocation(line.lineNo(), line.skuId(),
                    line.amount(), total, couponShares));
        }
        return result;
    }

    /** 把请求里的商品行标准化：补行号（L1/L2/...）、主体、查重、算行金额 */
    private List<Line> toLines(List<CalculateRequest.OrderItem> items, boolean partyEnabled) {
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "订单商品不能为空");
        }
        List<Line> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            CalculateRequest.OrderItem item = items.get(i);
            String lineNo = item.lineNo();
            if (lineNo == null || lineNo.isBlank()) {
                lineNo = "L" + (i + 1);
            }
            if (lineNo.length() > 64) {
                throw new BizException(ErrorCode.PARAM_ERROR, "商品行号最长 64 字符: " + lineNo);
            }
            if (!seen.add(lineNo)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "商品行号重复: " + lineNo);
            }
            String party = partyEnabled ? normalizeParty(item.settlePartyId()) : DEFAULT_PARTY;
            lines.add(new Line(lineNo, item.skuId(), item.categoryId(), item.price(),
                    item.quantity(), item.price() * item.quantity(), party));
        }
        return lines;
    }

    /**
     * 只做行标准化（补行号、按灰度归一主体、查重），不算钱。
     * 价格快照用它生成请求摘要——摘要必须与真正参与计算的行完全一致，
     * 否则“同摘要复用”会命中一份行集合不同的快照。
     */
    public List<LineView> normalizeLines(List<CalculateRequest.OrderItem> items,
                                         boolean partyEnabled) {
        return toLines(items, partyEnabled).stream()
                .map(l -> new LineView(l.lineNo(), l.skuId(), l.categoryId(), l.price(),
                        l.quantity(), l.amount(), l.settlePartyId()))
                .toList();
    }

    private static final String DEFAULT_PARTY = "__default__";

    private String normalizeParty(String party) {
        if (party == null || party.isBlank()) {
            return DEFAULT_PARTY;
        }
        String trimmed = party.trim();
        if (trimmed.length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "结算主体最长 64 字符: " + trimmed);
        }
        return trimmed;
    }

    private static boolean isMerchantOrSplit(CouponTemplate template) {
        return template.getBudgetOwner() == com.newcoupon.domain.coupon.BudgetOwner.MERCHANT
                || template.getBudgetOwner() == com.newcoupon.domain.coupon.BudgetOwner.SPLIT;
    }

    private static boolean isMissingParty(String party) {
        return party == null || party.isBlank() || DEFAULT_PARTY.equals(party);
    }

    private void warnLegacyPartyOnce(CouponTemplate template) {
        Long id = template.getId();
        if (id != null && legacyPartyWarned.add(id)) {
            log.warn("LEGACY_PARTY_MISSING templateId={} budgetOwner={} settlePartyId={} ——"
                            + " 旧商家/共担模板没有真实主体，按不可用处理；请迁移或新建模板",
                    id, template.getBudgetOwner(), template.getSettlePartyId());
        }
    }

    /** 范围/主体过滤为空时的可读原因：先看主体，再看范围 */
    private String scopeReason(CouponTemplate template, List<Line> lines, boolean partyEnabled) {
        String templateParty = template.getSettlePartyId();
        if (partyEnabled && templateParty != null && !templateParty.isBlank()
                && !DEFAULT_PARTY.equals(templateParty)) {
            boolean anyLineMatchesParty = lines.stream()
                    .anyMatch(line -> templateParty.equals(line.settlePartyId()));
            if (!anyLineMatchesParty) {
                return "订单行不属于券绑定的结算主体（" + templateParty + "）";
            }
        }
        if (template.getApplicableScope() != ApplicableScope.ALL) {
            return "商品不在券的适用范围内（" + template.getApplicableScope().getDesc() + "）";
        }
        return "没有可参与分摊的订单行";
    }

    private OrderContext toContext(Long userId, List<Line> lines, LocalDateTime now) {
        long orderAmount = lines.stream().mapToLong(Line::amount).sum();
        return OrderContext.builder()
                .userId(userId)
                .orderAmount(orderAmount)
                .items(toOrderItems(lines))
                .now(now)
                .build();
    }

    /** 策略只读 orderAmount；items 原样带入，保持上下文完整 */
    private OrderContext scopedContext(long amount, List<Line> lines, LocalDateTime now) {
        return OrderContext.builder()
                .orderAmount(amount)
                .items(toOrderItems(lines))
                .now(now)
                .build();
    }

    private List<OrderContext.OrderItem> toOrderItems(List<Line> lines) {
        return lines.stream()
                .map(l -> new OrderContext.OrderItem(l.lineNo(), l.skuId(), l.categoryId(),
                        l.price(), l.quantity()))
                .toList();
    }

    /**
     * 过滤出模板范围内、且属于模板绑定结算主体的商品行索引。
     *
     * <p>顺序：先按主体过滤，再按品类/SKU 过滤。商家 A 的券不能拿商家 B 的金额凑门槛；
     * 平台券（模板主体为空/__default__）不按主体过滤，行主体由订单行提供。
     */
    private int[] scopeIndices(CouponTemplate template, List<Line> lines, boolean partyEnabled) {
        ApplicableScope scope = template.getApplicableScope();
        Set<String> allowed = scope == ApplicableScope.ALL
                ? Set.of() : parseScopeIds(template.getScopeIds());
        if (scope != ApplicableScope.ALL && allowed.isEmpty()) {
            // 非 ALL 但没配范围值 = 配置错误。旧版会放行全部商品，等于把品类券变成全场券；
            // 现在按“不可用”处理，宁可拒绝也不越权给优惠。
            return new int[0];
        }
        String templateParty = template.getSettlePartyId();
        boolean filterParty = partyEnabled && templateParty != null && !templateParty.isBlank()
                && !DEFAULT_PARTY.equals(templateParty);

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (filterParty && !templateParty.equals(line.settlePartyId())) {
                continue;
            }
            if (scope != ApplicableScope.ALL) {
                String value = scope == ApplicableScope.CATEGORY ? line.categoryId() : line.skuId();
                if (!allowed.contains(value)) {
                    continue;
                }
            }
            indices.add(i);
        }
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }

    private Set<String> parseScopeIds(String scopeIds) {
        if (scopeIds == null || scopeIds.isBlank()) {
            return Set.of();
        }
        String cleaned = scopeIds.replaceAll("[\\[\\]\"]", "").trim();
        if (cleaned.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(cleaned.split(",\\s*"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private long sumRemaining(int[] indices, long[] remaining) {
        long sum = 0;
        for (int idx : indices) {
            sum += remaining[idx];
        }
        return sum;
    }

    /** 内部行模型：行号 + 商品 + 行金额 + 结算主体 */
    private record Line(String lineNo, String skuId, String categoryId,
                        long price, int quantity, long amount, String settlePartyId) {
    }

    /** 候选券：券 + 模板 + 范围行索引 */
    private record CandidateCoupon(UserCoupon coupon, CouponTemplate template, int[] scopeIndices) {
    }

    /** 已算出名义优惠的候选券（尚未分配） */
    private record PricedCandidate(CandidateCoupon candidate, long nominalDiscount) {
    }

    /** 已中选并完成行分摊的券 */
    private record AppliedCoupon(CandidateCoupon candidate, long effectiveDiscount, long[] shares) {
    }

    /** 整栈结果：中选券（按应用顺序）+ 不可用原因 + 被互斥淘汰的券 */
    private record StackResult(List<AppliedCoupon> applied, Map<Long, String> reasons,
                               Set<Long> excluded) {
        long totalDiscount() {
            return applied.stream().mapToLong(AppliedCoupon::effectiveDiscount).sum();
        }
    }
}
