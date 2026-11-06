package com.newcoupon.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.pricing.PricingSnapshotPayload;
import com.newcoupon.application.service.discount.DiscountCalculatorService;
import com.newcoupon.application.service.discount.DiscountCalculatorService.CalculationOutcome;
import com.newcoupon.application.service.discount.DiscountCalculatorService.ConfirmLineShare;
import com.newcoupon.application.service.discount.DiscountCalculatorService.LineView;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.BudgetOwner;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.pricing.PricingSnapshot;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.PricingSnapshotRepository;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.open.api.dto.CalculateResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 价格快照服务（环 2）：写快照、同摘要复用、核销前校验与冻结结果还原。
 *
 * <p><b>三条硬规则</b>：
 * <ol>
 *   <li><b>一次试算 = 一份快照</b>：带 orderNo 的 calculate 先按摘要查有效快照；
 *       命中就直接返回快照里存的响应（不再重算）——否则“模板在两次试算之间变了”
 *       会让返回的金额与快照金额不一致；</li>
 *   <li><b>带 pricingId 一律 fail-closed</b>：不存在/过期/归属不符/券集合不一致/
 *       行不一致都返回 4007，绝不静默回退到按当前规则重算；</li>
 *   <li><b>核销只读冻结结果</b>：行、券规则、每券每行分摊、出资比例都来自 payload，
 *       不重新读模板、不重新调用扩展点。</li>
 * </ol>
 *
 * <p><b>L70 前置契约</b>：快照的“可用券集合”必须等于订单当前生效的占用单集合
 * （占用中 + 已核销）。支付前少锁一张券 → 核销 fail-closed；这是刻意的，
 * 否则“试算=核销”在少锁券时是假的。
 */
@Service
public class PricingSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(PricingSnapshotService.class);

    /** payload 结构版本；不兼容时 fail-closed（核销）/撤销重算（试算复用） */
    public static final int PAYLOAD_VERSION = 1;
    /** 计算/分摊算法版本；算法升级后旧快照仍按旧语义读取 */
    public static final String ENGINE_VERSION = "ring2-v1";
    /** payload 硬上限：超限拒绝生成，不静默降级（100 行 × 10 券正常在 100~300KB） */
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private final PricingSnapshotRepository repository;
    private final CouponUsageRepository couponUsageRepository;
    private final DiscountCalculatorService calculator;
    private final ObjectMapper objectMapper;
    private final long ttlMinutes;

    public PricingSnapshotService(PricingSnapshotRepository repository,
                                  CouponUsageRepository couponUsageRepository,
                                  DiscountCalculatorService calculator,
                                  ObjectMapper objectMapper,
                                  @Value("${coupon.trade.pricing.ttl-minutes:120}") long ttlMinutes) {
        this.repository = repository;
        this.couponUsageRepository = couponUsageRepository;
        this.calculator = calculator;
        this.objectMapper = objectMapper;
        this.ttlMinutes = ttlMinutes;
    }

    /**
     * 请求摘要：appId + userId + orderNo + 主体灰度 + 标准化行 + 排序后的券集合。
     *
     * <p>行必须用“真正参与计算的行”（补行号、归一主体），否则同一请求的两种写法
     * 会算出不同摘要，同摘要复用失效；主体灰度开关也进摘要，避免开关切换后
     * 复用一份按旧口径过滤主体生成的快照。
     */
    public String digest(String appId, CalculateRequest request, boolean partyEnabled) {
        List<LineView> lines = calculator.normalizeLines(request.items(), partyEnabled);
        List<Long> couponIds = request.couponIds().stream().sorted().toList();
        DigestInput input = new DigestInput(appId, request.userId(), request.orderNo(),
                partyEnabled, lines, couponIds);
        return sha256(toJson(input));
    }

    /**
     * 同摘要复用：命中有效快照 → 返回快照里冻结的响应（含 pricingId）。
     * 过期/损坏/绑定不符 → 删除并返回 empty（调用方重新试算）。
     */
    public Optional<CalculateResponse> findReusable(String digest, String appId, long userId,
                                                    String orderNo, LocalDateTime now) {
        Optional<PricingSnapshot> found = repository.findLiveByDigest(appId, userId, orderNo, digest);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PricingSnapshot snapshot = found.get();
        if (snapshot.liveDigest() == null || snapshot.expiredAt(now)) {
            repository.clearLiveDigestIfExpired(snapshot.pricingId(), now);
            return Optional.empty();
        }
        if (!snapshot.appId().equals(appId) || snapshot.userId() != userId
                || !snapshot.orderNo().equals(orderNo)) {
            log.error("PRICING_SNAPSHOT_BINDING_MISMATCH pricingId={} appId={} userId={} orderNo={}",
                    snapshot.pricingId(), appId, userId, orderNo);
            discard(snapshot);
            return Optional.empty();
        }
        PricingSnapshotPayload payload = readPayload(snapshot);
        if (payload == null || payload.version() != PAYLOAD_VERSION
                || !payload.appId().equals(appId) || payload.userId() != userId
                || !payload.orderNo().equals(orderNo)) {
            log.error("PRICING_SNAPSHOT_REUSE_CORRUPT pricingId={}", snapshot.pricingId());
            discard(snapshot);
            return Optional.empty();
        }
        log.info("PRICING_SNAPSHOT_REUSED pricingId={} orderNo={} coupons={}",
                snapshot.pricingId(), orderNo, snapshot.couponSet());
        return Optional.of(toResponse(payload).withPricingId(snapshot.pricingId()));
    }

    /**
     * 锁券前校验（不写任何东西）：快照存在、未过期、归属匹配、该券在快照的可用集合内。
     * 锁券本身不依赖快照价格；这一步只防止“拿一份不可用券的快照去锁券”。
     */
    public void assertLockable(String pricingId, String orderNo, long userId, long couponId,
                               LocalDateTime now) {
        PricingSnapshot snapshot = repository.findByPricingId(pricingId)
                .orElseThrow(() -> invalid("价格快照不存在"));
        if (snapshot.liveDigest() == null) {
            throw invalid("价格快照已失效");
        }
        if (snapshot.expiredAt(now)) {
            throw invalid("价格快照已过期");
        }
        String callerAppId = AppOwnership.currentAppId();
        if (callerAppId != null && !callerAppId.equals(snapshot.appId())) {
            throw invalid("价格快照不存在或不属于当前应用");
        }
        if (snapshot.userId() != userId || !snapshot.orderNo().equals(orderNo)) {
            throw invalid("价格快照与锁券请求的用户/订单不一致");
        }
        PricingSnapshotPayload payload = readPayload(snapshot);
        if (payload == null || payload.version() != PAYLOAD_VERSION) {
            throw invalid("价格快照格式不支持或已损坏");
        }
        PricingSnapshotPayload.Coupon coupon = payload.coupons().stream()
                .filter(c -> c.couponId() == couponId)
                .findFirst()
                .orElseThrow(() -> invalid("券不在价格快照中"));
        if (!coupon.applied()) {
            throw invalid("该券在价格快照中不可用: " + coupon.unusableReason());
        }
    }

    /**
     * 锁券时续期：把快照有效期抬到“锁窗 + 核销宽限”，并记 last_bound_at。
     * 返回 false = 快照已过期/撤销；锁券事务必须整体回滚（4007）。
     */
    public boolean renewOnLock(String pricingId, LocalDateTime minExpiresAt, LocalDateTime now) {
        return repository.renewOnLock(pricingId, minExpiresAt, now);
    }

    /**
     * 新建快照并返回带 pricingId 的响应。并发同摘要撞唯一键 → 回读已有快照；
     * 已有快照恰好过期 → 清空 live_digest 后重试一次。
     */
    public CalculateResponse create(String digest, CalculateRequest request,
                                    CalculationOutcome outcome, String appId,
                                    boolean settlePartyEnabled, int maxDiscountRatio,
                                    LocalDateTime now) {
        if (request.orderNo() == null || request.orderNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "写价格快照必须提供 orderNo");
        }
        CalculateResponse response = outcome.response();
        List<PricingSnapshotPayload.Coupon> coupons = buildCoupons(response, outcome);
        List<Long> appliedIds = coupons.stream()
                .filter(PricingSnapshotPayload.Coupon::applied)
                .map(PricingSnapshotPayload.Coupon::couponId)
                .sorted()
                .toList();
        String couponSet = appliedIds.stream().map(String::valueOf)
                .collect(Collectors.joining(","));
        PricingSnapshotPayload payload = new PricingSnapshotPayload(
                PAYLOAD_VERSION, ENGINE_VERSION, appId, request.userId(), request.orderNo(),
                digest, settlePartyEnabled, maxDiscountRatio,
                outcome.lines().stream()
                        .map(l -> new PricingSnapshotPayload.Line(l.lineNo(), l.skuId(),
                                l.categoryId(), l.price(), l.quantity(), l.lineAmount(),
                                l.settlePartyId()))
                        .toList(),
                coupons,
                toPayloadResponse(response),
                List.of(),
                now);
        String payloadJson = toJson(payload);
        int payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > MAX_PAYLOAD_BYTES) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "价格快照过大（" + payloadBytes + " 字节，上限 " + MAX_PAYLOAD_BYTES + "）");
        }
        LocalDateTime expiresAt = now.plusMinutes(Math.max(0L, ttlMinutes));
        long original = nz(response.originalPrice());
        long discount = nz(response.discountAmount());
        long payable = nz(response.finalPrice());
        PricingSnapshot toSave = new PricingSnapshot(null, null, appId, request.userId(),
                request.orderNo(), couponSet, digest, digest, payloadJson, PAYLOAD_VERSION,
                ENGINE_VERSION, payloadBytes, outcome.lines().size(), appliedIds.size(),
                original, discount, payable, expiresAt, null, now);
        try {
            PricingSnapshot saved = repository.save(toSave);
            log.info("PRICING_SNAPSHOT_CREATED pricingId={} orderNo={} coupons={} bytes={} expiresAt={}",
                    saved.pricingId(), request.orderNo(), couponSet, payloadBytes, expiresAt);
            return response.withPricingId(saved.pricingId());
        } catch (DuplicateKeyException e) {
            return resolveConcurrentCreate(appId, request.userId(), request.orderNo(),
                    digest, toSave, now);
        }
    }

    private CalculateResponse resolveConcurrentCreate(String appId, long userId, String orderNo,
                                                      String digest, PricingSnapshot toSave,
                                                      LocalDateTime now) {
        Optional<PricingSnapshot> existing = repository.findLiveByDigest(
                appId, userId, orderNo, digest);
        if (existing.isPresent() && !existing.get().expiredAt(now)) {
            PricingSnapshot winner = existing.get();
            return toResponse(readRequiredPayload(winner)).withPricingId(winner.pricingId());
        }
        if (existing.isPresent()) {
            // 过期但还没被清理占着 live_digest：先置空再重建（历史行保留）
            repository.clearLiveDigestIfExpired(existing.get().pricingId(), now);
        } else {
            repository.clearExpiredLiveByDigest(appId, userId, orderNo, digest, now);
        }
        try {
            PricingSnapshot saved = repository.save(toSave);
            return toResponse(readRequiredPayload(saved)).withPricingId(saved.pricingId());
        } catch (DuplicateKeyException again) {
            Optional<PricingSnapshot> raced = repository.findLiveByDigest(
                    appId, userId, orderNo, digest);
            if (raced.isPresent() && !raced.get().expiredAt(now)) {
                return toResponse(readRequiredPayload(raced.get()))
                        .withPricingId(raced.get().pricingId());
            }
            throw new BizException(ErrorCode.SYSTEM_ERROR, "价格快照并发重建失败");
        }
    }

    /**
     * 核销前校验并还原冻结结果。
     *
     * @param includeExceptionStatus 人工解除核销异常时，把当前占用单的 CONFIRM_EXCEPTION
     *                               也算进“订单生效券集合”（正常核销传 false）
     */
    public SnapshotConfirm prepareConfirm(String pricingId, CouponUsage usage,
                                          List<CalculateRequest.OrderItem> items,
                                          boolean includeExceptionStatus, LocalDateTime now) {
        PricingSnapshot snapshot = repository.findByPricingId(pricingId)
                .orElseThrow(() -> invalid("价格快照不存在"));
        if (snapshot.liveDigest() == null) {
            throw invalid("价格快照已失效");
        }
        if (snapshot.expiredAt(now)) {
            throw invalid("价格快照已过期");
        }
        // 开放面必须同租户；内部面 appId=null 是平台管理员语义，放行（与 AppOwnership 一致）
        String callerAppId = AppOwnership.currentAppId();
        if (callerAppId != null && !callerAppId.equals(snapshot.appId())) {
            throw invalid("价格快照不存在或不属于当前应用");
        }
        if (snapshot.userId() != usage.getUserId()
                || !snapshot.orderNo().equals(usage.getOrderNo())) {
            throw invalid("价格快照与占用单的用户/订单不一致");
        }
        PricingSnapshotPayload payload = readPayload(snapshot);
        if (payload == null || payload.version() != PAYLOAD_VERSION) {
            throw invalid("价格快照格式不支持或已损坏");
        }
        if (!payload.appId().equals(snapshot.appId())
                || payload.userId() != snapshot.userId()
                || !payload.orderNo().equals(snapshot.orderNo())) {
            throw invalid("价格快照索引与内容不一致");
        }

        Set<Long> applied = payload.coupons().stream()
                .filter(PricingSnapshotPayload.Coupon::applied)
                .map(PricingSnapshotPayload.Coupon::couponId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> active = activeCouponSet(usage, includeExceptionStatus);
        if (!active.equals(applied)) {
            throw invalid("价格快照券集合与订单占用单不一致（支付前需锁完全部券）: 快照="
                    + applied + " 订单=" + active);
        }

        PricingSnapshotPayload.Coupon current = payload.coupons().stream()
                .filter(c -> c.couponId() == usage.getUserCouponId())
                .findFirst()
                .orElseThrow(() -> invalid("当前券不在价格快照中"));
        if (!current.applied()) {
            throw invalid("该券在价格快照中不可用: " + current.unusableReason());
        }
        if (items != null && !items.isEmpty()) {
            validateItems(items, payload.lines());
        }

        Map<String, PricingSnapshotPayload.Line> lineByNo = payload.lines().stream()
                .collect(Collectors.toMap(PricingSnapshotPayload.Line::lineNo, l -> l,
                        (a, b) -> a, LinkedHashMap::new));
        List<ConfirmLineShare> shares = new ArrayList<>();
        long sum = 0;
        for (PricingSnapshotPayload.LineShare share : current.lineShares()) {
            PricingSnapshotPayload.Line line = lineByNo.get(share.lineNo());
            if (line == null) {
                throw invalid("价格快照缺少商品行: " + share.lineNo());
            }
            if (share.discountAmount() <= 0) {
                continue;
            }
            if (share.discountAmount() > line.lineAmount()) {
                throw invalid("价格快照行分摊超过行金额: " + share.lineNo());
            }
            shares.add(new ConfirmLineShare(line.lineNo(), line.skuId(), line.lineAmount(),
                    line.quantity(), share.discountAmount(), line.settlePartyId()));
            sum += share.discountAmount();
        }
        if (sum != current.discountAmount()) {
            throw invalid("价格快照行分摊不守恒");
        }
        if (shares.isEmpty()) {
            throw invalid("价格快照没有可核销的行分摊");
        }
        validateFunding(current);
        long orderAmount = payload.lines().stream()
                .mapToLong(PricingSnapshotPayload.Line::lineAmount).sum();
        return new SnapshotConfirm(snapshot.pricingId(), current.discountAmount(),
                current.budgetOwner(), current.budgetRatio(), shares, orderAmount,
                payload.maxDiscountRatio(), applied);
    }

    private Set<Long> activeCouponSet(CouponUsage usage, boolean includeExceptionStatus) {
        EnumSet<UsageStatus> statuses = EnumSet.of(UsageStatus.OCCUPYING, UsageStatus.CONFIRMED);
        if (includeExceptionStatus) {
            statuses.add(UsageStatus.CONFIRM_EXCEPTION);
        }
        return couponUsageRepository.findByOrderNo(usage.getUserId(), usage.getOrderNo()).stream()
                .filter(u -> statuses.contains(u.getStatus()))
                .map(CouponUsage::getUserCouponId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void validateItems(List<CalculateRequest.OrderItem> items,
                               List<PricingSnapshotPayload.Line> lines) {
        if (items.size() != lines.size()) {
            throw invalid("核销商品行数与价格快照不一致");
        }
        for (int i = 0; i < items.size(); i++) {
            CalculateRequest.OrderItem item = items.get(i);
            PricingSnapshotPayload.Line line = lines.get(i);
            if (item == null || item.skuId() == null || item.categoryId() == null
                    || item.price() == null || item.quantity() == null) {
                throw invalid("核销商品行缺少必填字段");
            }
            String lineNo = item.lineNo() == null || item.lineNo().isBlank()
                    ? "L" + (i + 1) : item.lineNo();
            if (!lineNo.equals(line.lineNo())
                    || !item.skuId().equals(line.skuId())
                    || !item.categoryId().equals(line.categoryId())
                    || item.price() != line.price()
                    || item.quantity() != line.quantity()) {
                throw invalid("核销商品行与价格快照不一致: " + lineNo);
            }
            if (item.settlePartyId() != null && !item.settlePartyId().isBlank()
                    && !item.settlePartyId().trim().equals(line.settlePartyId())) {
                throw invalid("核销商品行结算主体与价格快照不一致: " + lineNo);
            }
        }
    }

    private void validateFunding(PricingSnapshotPayload.Coupon coupon) {
        BudgetOwner owner;
        try {
            owner = BudgetOwner.valueOf(coupon.budgetOwner());
        } catch (RuntimeException e) {
            throw invalid("价格快照出资方不合法");
        }
        if (owner == BudgetOwner.SPLIT) {
            Integer ratio = coupon.budgetRatio();
            if (ratio == null || ratio < 1 || ratio > 99) {
                throw invalid("价格快照共担比例不合法");
            }
        }
    }

    private List<PricingSnapshotPayload.Coupon> buildCoupons(CalculateResponse response,
                                                             CalculationOutcome outcome) {
        List<PricingSnapshotPayload.Coupon> coupons = new ArrayList<>();
        for (CalculateResponse.CouponUsage used : response.usedCoupons()) {
            CouponTemplate template = outcome.appliedTemplates().get(used.couponId());
            if (template == null) {
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "价格快照缺少模板: couponId=" + used.couponId());
            }
            List<PricingSnapshotPayload.LineShare> shares = new ArrayList<>();
            for (CalculateResponse.LineAllocation allocation : response.allocations()) {
                for (CalculateResponse.CouponLineShare share : allocation.couponShares()) {
                    if (share.couponId().equals(used.couponId())
                            && share.amount() != null && share.amount() > 0) {
                        shares.add(new PricingSnapshotPayload.LineShare(
                                allocation.lineNo(), share.amount()));
                    }
                }
            }
            long usedAmount = used.discountAmount() == null ? 0 : used.discountAmount();
            long sum = shares.stream()
                    .mapToLong(PricingSnapshotPayload.LineShare::discountAmount).sum();
            if (sum != usedAmount) {
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "价格快照行分摊不守恒: couponId=" + used.couponId());
            }
            coupons.add(new PricingSnapshotPayload.Coupon(
                    used.couponId(), template.getId(), template.getTemplateCode(),
                    template.getName(), template.getCouponType().name(), template.getFaceValue(),
                    template.getDiscountCap(), template.getThreshold(),
                    template.getStackingLayer().name(), template.getExclusionGroup(),
                    template.getApplicableScope().name(), template.getScopeIds(),
                    template.getBudgetOwner().name(), template.getBudgetRatio(),
                    template.getSettlePartyId(), true, usedAmount, null, shares));
        }
        for (CalculateResponse.CouponReason reason : response.unusableCoupons()) {
            coupons.add(new PricingSnapshotPayload.Coupon(
                    reason.couponId(), null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, false, 0,
                    reason.reason(), List.of()));
        }
        return coupons;
    }

    private PricingSnapshotPayload.Response toPayloadResponse(CalculateResponse response) {
        return new PricingSnapshotPayload.Response(
                nz(response.originalPrice()), nz(response.discountAmount()),
                nz(response.finalPrice()),
                response.usedCoupons().stream()
                        .map(u -> new PricingSnapshotPayload.UsedCoupon(u.couponId(),
                                u.templateName(), u.layer(), nz(u.discountAmount())))
                        .toList(),
                response.allocations().stream()
                        .map(a -> new PricingSnapshotPayload.Allocation(a.lineNo(), a.skuId(),
                                nz(a.lineAmount()), nz(a.allocatedDiscount()),
                                a.couponShares().stream()
                                        .map(c -> new PricingSnapshotPayload.CouponShare(
                                                c.couponId(), nz(c.amount())))
                                        .toList()))
                        .toList(),
                response.unusableCoupons().stream()
                        .map(r -> new PricingSnapshotPayload.Reason(r.couponId(), r.reason()))
                        .toList());
    }

    private CalculateResponse toResponse(PricingSnapshotPayload payload) {
        PricingSnapshotPayload.Response r = payload.response();
        return new CalculateResponse(r.originalPrice(), r.discountAmount(), r.finalPrice(),
                r.usedCoupons().stream()
                        .map(u -> new CalculateResponse.CouponUsage(u.couponId(),
                                u.templateName(), u.layer(), u.discountAmount()))
                        .toList(),
                r.allocations().stream()
                        .map(a -> new CalculateResponse.LineAllocation(a.lineNo(), a.skuId(),
                                a.lineAmount(), a.allocatedDiscount(),
                                a.couponShares().stream()
                                        .map(c -> new CalculateResponse.CouponLineShare(
                                                c.couponId(), c.amount()))
                                        .toList()))
                        .toList(),
                r.unusableCoupons().stream()
                        .map(x -> new CalculateResponse.CouponReason(x.couponId(), x.reason()))
                        .toList());
    }

    private PricingSnapshotPayload readRequiredPayload(PricingSnapshot snapshot) {
        PricingSnapshotPayload payload = readPayload(snapshot);
        if (payload == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "价格快照内容损坏: pricingId=" + snapshot.pricingId());
        }
        return payload;
    }

    private PricingSnapshotPayload readPayload(PricingSnapshot snapshot) {
        if (snapshot.payload() == null || snapshot.payload().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot.payload(), PricingSnapshotPayload.class);
        } catch (Exception e) {
            log.error("PRICING_SNAPSHOT_PAYLOAD_CORRUPT pricingId={} err={}",
                    snapshot.pricingId(), e.toString());
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "价格快照序列化失败: " + e.getMessage());
        }
    }

    /** 损坏/绑定不符：撤销 live_digest（不物理删除，保留审计），让同摘要可以重建 */
    private void discard(PricingSnapshot snapshot) {
        try {
            repository.revoke(snapshot.pricingId());
            log.warn("PRICING_SNAPSHOT_DISCARDED pricingId={}", snapshot.pricingId());
        } catch (RuntimeException e) {
            log.warn("PRICING_SNAPSHOT_DISCARD_FAILED pricingId={} err={}",
                    snapshot.pricingId(), e.toString());
        }
    }

    private static BizException invalid(String detail) {
        return new BizException(ErrorCode.PRICING_SNAPSHOT_INVALID, detail);
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 摘要输入：字段顺序即序列化顺序，保证同一请求每次摘要完全一致 */
    private record DigestInput(String appId, long userId, String orderNo, boolean partyEnabled,
                               List<LineView> lines, List<Long> couponIds) {
    }

    /**
     * 核销所需的冻结结果。TradeAppService 用 budgetOwner/budgetRatio 做出资拆分，
     * 用 lineShares 落行级分账；orderAmount 供申报防线（优惠占比上限）使用。
     *
     * <p>{@code appliedCouponIds} 是快照冻结的“可用券集合”（APPLIED 部分）——事务层
     * L75 拿它在锁内重校验“快照券集合 = 订单当前生效占用单集合”。事务外的同名校验
     * 保留做快速失败，但只有锁后比对才是权威。
     */
    public record SnapshotConfirm(String pricingId, long discountAmt, String budgetOwner,
                                  Integer budgetRatio, List<ConfirmLineShare> lineShares,
                                  long orderAmount,
                                  /** 生成快照时生效的优惠占比上限；核销按冻结值判，不读当前配置 */
                                  int maxDiscountRatio,
                                  Set<Long> appliedCouponIds) {
    }
}
