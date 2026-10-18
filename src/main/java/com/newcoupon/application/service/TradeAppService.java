package com.newcoupon.application.service;

import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.dto.TradeDtos.RefundResult;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmResult;
import com.newcoupon.application.dto.TradeDtos.FundingSplit;
import com.newcoupon.application.dto.TradeDtos.LineShare;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.MyCoupon;
import com.newcoupon.application.dto.TradeDtos.MyCouponList;
import com.newcoupon.application.dto.TradeDtos.ReleaseRequest;
import com.newcoupon.application.dto.TradeDtos.ReleaseResult;
import com.newcoupon.common.AuditLog;
import com.newcoupon.common.BizException;
import com.newcoupon.common.BoundedKey;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.application.service.discount.DiscountCalculatorService;
import com.newcoupon.application.service.discount.DiscountCalculatorService.ConfirmLineShare;
import com.newcoupon.domain.coupon.BudgetOwner;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.pricing.PricingUsage;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.PricingUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 用券编排层。⚠️ 严禁 @Transactional（原因同 ReceiveAppService：rollback-only 退化，
 * 见 study/04a 第 4 部分）。
 *
 * <p>职责：①调用事务层执行三段 ②接住 CAS 信号/DuplicateKey，用事务外新快照分类成
 * 准确的幂等成功或状态冲突错误 ③券包查询。
 */
@Service
public class TradeAppService {

    private static final Logger log = LoggerFactory.getLogger(TradeAppService.class);

    private final TradeTxService tradeTxService;
    private final UserCouponRepository userCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final DiscountCalculatorService discountCalculatorService;
    private final PricingSnapshotService pricingSnapshotService;
    private final PricingUsageRepository pricingUsageRepository;
    private final RefundPolicyService refundPolicyService;
    private final TradeMetrics tradeMetrics;

    /** 申报防线①：单笔优惠占订单金额的上限（百分数，默认 80）。极端促销（0元购）可调高 */
    private final int maxDiscountRatio;

    /** 申报防线②：单接入方核销速率告警阈值（次/分钟，默认 600）——只告警不拦截（fail-open） */
    private final int confirmRateLimit;

    /** 速率窗口：appId → [窗口起点毫秒, 计数]。单机内存版——多实例部署时告警聚合在日志侧 */
    private final ConcurrentHashMap<String, long[]> confirmRateWindow = new ConcurrentHashMap<>();

    private static final long RATE_WINDOW_MS = 60_000;

    public TradeAppService(TradeTxService tradeTxService,
                           UserCouponRepository userCouponRepository,
                           CouponUsageRepository couponUsageRepository,
                           DiscountCalculatorService discountCalculatorService,
                           PricingSnapshotService pricingSnapshotService,
                           PricingUsageRepository pricingUsageRepository,
                           RefundPolicyService refundPolicyService,
                           TradeMetrics tradeMetrics,
                           @Value("${coupon.trade.max-discount-ratio:80}") int maxDiscountRatio,
                           @Value("${coupon.trade.confirm-rate-per-minute:600}") int confirmRateLimit) {
        this.tradeTxService = tradeTxService;
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.discountCalculatorService = discountCalculatorService;
        this.pricingSnapshotService = pricingSnapshotService;
        this.pricingUsageRepository = pricingUsageRepository;
        this.refundPolicyService = refundPolicyService;
        this.tradeMetrics = tradeMetrics;
        this.maxDiscountRatio = maxDiscountRatio;
        this.confirmRateLimit = confirmRateLimit;
    }

    public LockResult lock(LockRequest req) {
        RequestContext.current().user(req.userId());   // S1 治理插槽：内部面自动 no-op
        // S1 越权防线：先确认这张券属于调用方。查不到券时不在这里报错——交给下面的原有逻辑报 4001，
        // 免得把"券不存在"和"券不属于你"变成两条不同的早期分支
        userCouponRepository.findById(req.couponId())
                .ifPresent(c -> AppOwnership.assertAccessible(c.getAppId(), "coupon:" + c.getId()));
        // 幂等快查（与领券同构）：锁券的幂等证据在占用单（usageNo 唯一），不在券状态上——
        // 必须在进事务前先查，否则事务层对券状态的内存快检会先抛冲突（E2E 抓到的真实Bug）
        // 与事务层使用同一个有界键：长 orderNo 会被折叠，快查/幂等重建必须用同一规则
        String usageNo = BoundedKey.usageNo(req.orderNo(), req.couponId());
        var existingUsage = couponUsageRepository.findByUsageNo(usageNo);
        if (existingUsage.isPresent()) {
            // F2（2026-09-15 随 L5 修复）：只有"占用中"才能答"已锁定"——合同已终结
            // （已释放/过期关闭/超时关闭）时券已回池甚至易主，报成功是谎报
            CouponUsage u = existingUsage.get();
            if (u.getStatus() == UsageStatus.OCCUPYING) {
                return new LockResult(usageNo, req.couponId(), CouponStatus.LOCKED.getDesc(), true);
            }
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "该订单的占用已终结（" + u.getStatus().getDesc() + "），请使用新订单号");
        }

        // 环 2：带 pricingId 的锁券先做快照绑定校验（快照不锁价，核销时才按冻结价）。
        // 幂等快查在前面：已锁成功的重放即使快照过期也应答成功，不因快照失效破坏幂等。
        boolean pricingRequested = req.pricingId() != null && !req.pricingId().isBlank();
        LocalDateTime now = LocalDateTime.now();
        if (pricingRequested) {
            pricingSnapshotService.assertLockable(req.pricingId(), req.orderNo(),
                    req.userId(), req.couponId(), now);
        }

        try {
            if (pricingRequested) {
                return tradeTxService.lock(req, now, req.pricingId());
            }
            return tradeTxService.lock(req, now);
        } catch (DuplicateKeyException e) {
            // 撞 uk_usage_no = 同一订单并发重复锁（快查同时穿过）——回滚后重查当成功
            LockResult existing = idempotentLock(req);
            if (existing != null) {
                return existing;
            }
            log.error("lock duplicate key but no usage found: {}", req);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "锁券冲突分类失败");
        } catch (TradeCasRejectedException e) {
            // 券状态 CAS 失败：新快照读券的真实状态分类
            UserCoupon fresh = userCouponRepository.findById(req.couponId())
                    .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));
            if (fresh.getStatus() == CouponStatus.LOCKED) {
                LockResult existing = idempotentLock(req);
                if (existing != null) {
                    return existing;
                }
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "券已被其他订单锁定（orderNo=" + req.orderNo() + " 不是持有者）");
            }
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "券当前状态=" + fresh.getStatus().getDesc() + "，仅待使用可锁定");
        } catch (BizException e) {
            // 兜底：并发窗口内事务层内存快检读到"已锁定"抛的冲突——若占用单是本单的，按幂等处理
            if (e.getErrorCode() == ErrorCode.STATE_CONFLICT) {
                LockResult existing = idempotentLock(req);
                if (existing != null) {
                    return existing;
                }
            }
            throw e;
        }
    }

    /**
     * 支付成功核销（动钱操作）。第一个真实审计点（第 1 环验收测试写明"审计只给动钱的写操作用，
     * 第一个真实审计点将是核销"）——@AuditLog 切面采集六要素异步写审计表，失败不影响业务。
     * 已知局限：切面取 userId 的约定是"第一个 Long 入参"，本方法入参是 record——userId 记空，
     * 归属者可由 usageNo 在占用单反查（审计 before/after 快照里都带 usageNo）。
     */
    @AuditLog(action = "COUPON_CONFIRM", resourceType = "USAGE", describe = "核销占用单（动钱）")
    public ConfirmResult confirm(ConfirmRequest req) {
        ConfirmResult result;
        try {
            result = doConfirm(req);
        } catch (RuntimeException e) {
            safeMetric(() -> tradeMetrics.recordConfirmFailed());
            throw e;
        }
        safeMetric(() -> tradeMetrics.recordConfirm(result.duplicated(),
                result.discountAmt() == null ? 0 : result.discountAmt()));
        return result;
    }

    private ConfirmResult doConfirm(ConfirmRequest req) {
        assertUsageAccessible(req.usageNo());   // S1 越权防线（占用单只带 usageNo，需经券取归属）

        // 环 2：锁券带快照时，占用单上有唯一绑定。核销漏传 pricingId 也按绑定解析，
        // 防止调用方漏传后退回“按当前模板重算”——价格保证不能依赖调用方记性。
        String requestedPricingId = req.pricingId() == null || req.pricingId().isBlank()
                ? null : req.pricingId();
        String boundPricingId = pricingUsageRepository.findByUsageNo(req.usageNo())
                .map(PricingUsage::pricingId)
                .orElse(null);
        String effectivePricingId = requestedPricingId != null
                ? requestedPricingId : boundPricingId;
        boolean pricingRequested = effectivePricingId != null;
        boolean itemsPresent = req.items() != null && !req.items().isEmpty();
        // 契约兜实：老式核销（无 items/无 pricingId）必须带正数金额；带 pricingId 时
        // 金额来自快照，items 可省（省了就直接用快照行）。
        if (!pricingRequested && !itemsPresent
                && (req.discountAmt() == null || req.discountAmt() <= 0)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "核销必须提供 pricingId（快照算钱）、items（平台算钱）"
                            + "或正数 discountAmt（老式金额）其一");
        }

        // ════ 核销：快照冻结价 / 平台整栈复算 / 老式金额 三条路径 ════
        FundingSplit split = null;
        List<LineShare> lineShares = List.of();
        ConfirmRequest txReq = req;   // 不重赋值参数 req（lambda 要求 effectively final）
        if (pricingRequested || itemsPresent) {
            CouponUsage usage = couponUsageRepository.findByUsageNo(req.usageNo())
                    .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));

            // 幂等优先：已核销的重复回调直接返回首次金额，不再进入整栈复算
            // （否则兄弟券退款后重放一次核销，会被“订单已进入售后”误伤）。
            if (usage.getStatus() == UsageStatus.CONFIRMED) {
                return new ConfirmResult(req.usageNo(), CouponStatus.USED.getDesc(),
                        usage.getDiscountAmt(), true);
            }
            // 死状态（已退款/已释放/过期/超时）在算钱之前就给出明确答复，避免
            // “售后守卫”把 4004/4006 盖成笼统的 4002。
            usage.checkConfirmable();

            // 绑定一致性：锁券时用了哪份快照，核销就必须用同一份；
            // 锁券没带快照的占用单，不接受核销时临时塞一个 ID（否则可换价）。
            if (requestedPricingId != null && boundPricingId == null) {
                String message = "该占用单未绑定价格快照，不能只凭核销请求携带 pricingId";
                markConfirmExceptionQuietly(req.usageNo(), requestedPricingId, message);
                throw new BizException(ErrorCode.PRICING_SNAPSHOT_INVALID, message);
            }
            if (requestedPricingId != null && !requestedPricingId.equals(boundPricingId)) {
                String message = "核销 pricingId 与锁券时绑定的价格快照不一致";
                markConfirmExceptionQuietly(req.usageNo(), requestedPricingId, message);
                throw new BizException(ErrorCode.PRICING_SNAPSHOT_INVALID, message);
            }

            // ── L64：把同一订单的全部占用单找回来（快照校验/整栈复算都要）──
            List<CouponUsage> orderUsages = couponUsageRepository.findByOrderNo(
                    usage.getUserId(), usage.getOrderNo());
            boolean afterSales = orderUsages.stream().anyMatch(u ->
                    u.getStatus() == UsageStatus.REFUNDED
                            || u.getStatus() == UsageStatus.PARTIALLY_REFUNDED);
            if (afterSales) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "订单已进入售后（部分/全部退款），不能再核销其他券");
            }

            if (pricingRequested) {
                PricingSnapshotService.SnapshotConfirm snapshot;
                try {
                    snapshot = pricingSnapshotService.prepareConfirm(
                            effectivePricingId, usage, req.items(), false, LocalDateTime.now());
                } catch (BizException e) {
                    // 用户已支付但快照无法核销：先把占用单钉在 CONFIRM_EXCEPTION，
                    // 不让 30 分钟超时任务把券放回可用（双重优惠）。标记失败不掩盖原错误。
                    if (e.getErrorCode() == ErrorCode.PRICING_SNAPSHOT_INVALID) {
                        markConfirmExceptionQuietly(req.usageNo(), effectivePricingId, e.getMessage());
                    }
                    throw e;
                }

                // 申报防线①：优惠占比上限，用快照冻结的整单金额与冻结的上限判——
                // 当前配置在试算后改了，不能反过来改变已达成的报价。
                int ratioLimit = snapshot.maxDiscountRatio();
                long maxAllowed = snapshot.orderAmount() * ratioLimit / 100;
                if (snapshot.discountAmt() > maxAllowed) {
                    log.warn("DEFENSE DISCOUNT_RATIO usageNo={} discount={} order={} ratio>={}%",
                            req.usageNo(), snapshot.discountAmt(), snapshot.orderAmount(),
                            ratioLimit);
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            String.format("优惠金额(%d)超过订单金额(%d)的%d%%上限，拒绝核销",
                                    snapshot.discountAmt(), snapshot.orderAmount(), ratioLimit));
                }
                alarmConfirmRate();

                split = calculateFundingSplit(snapshot.budgetOwner(), snapshot.budgetRatio(),
                        snapshot.discountAmt());
                lineShares = buildLineShares(snapshot.lineShares(), split);
                txReq = new ConfirmRequest(req.usageNo(), snapshot.discountAmt(), null,
                        effectivePricingId);
            } else {
                UserCoupon coupon = userCouponRepository.findById(usage.getUserCouponId())
                        .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));

                // 只让“当前生效”的合同参与优惠栈：占用中 + 已核销；已释放/过期/超时的合同不属于本单
                Map<Long, UserCoupon> orderCoupons = new LinkedHashMap<>();
                for (CouponUsage sibling : orderUsages) {
                    if (sibling.getStatus() != UsageStatus.OCCUPYING
                            && sibling.getStatus() != UsageStatus.CONFIRMED) {
                        continue;
                    }
                    userCouponRepository.findByIdForUser(
                                    sibling.getUserCouponId(), sibling.getUserId())
                            .ifPresent(c -> orderCoupons.putIfAbsent(c.getId(), c));
                }
                orderCoupons.putIfAbsent(coupon.getId(), coupon);

                var calc = discountCalculatorService.calculateForOrderConfirm(coupon.getId(),
                        new ArrayList<>(orderCoupons.values()), req.items());

                // ── 申报防线（门槛/范围复核已由整栈复算先行完成）──
                // ①优惠占比上限：平台算出的优惠不得超过订单金额的 maxDiscountRatio%。
                // 防的是"合法高力度券 × 刚过门槛的小订单"（如满100减90配 100 元订单，占比 90%）——
                // 这类单笔合理、比例离谱的申报，直接拒绝；极端促销（0元购）由运营调高环境变量放行。
                long orderAmount = req.items().stream()
                        .mapToLong(i -> i.price() * i.quantity()).sum();
                long maxAllowed = orderAmount * maxDiscountRatio / 100;
                if (calc.discountAmt() > maxAllowed) {
                    log.warn("DEFENSE DISCOUNT_RATIO usageNo={} discount={} order={} ratio>={}%",
                            req.usageNo(), calc.discountAmt(), orderAmount, maxDiscountRatio);
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            String.format("优惠金额(%d)超过订单金额(%d)的%d%%上限，拒绝核销",
                                    calc.discountAmt(), orderAmount, maxDiscountRatio));
                }

                // ②申报速率告警：单接入方每分钟核销次数超阈值时告警（只记不拦——
                // 防线挡的是攻击者，不能反过来 brick 正常大促流量；拦截交给接入方治理）
                alarmConfirmRate();

                split = calculateFundingSplit(calc.template(), calc.discountAmt());
                lineShares = buildLineShares(calc.lineShares(), split);

                // 用平台计算出的金额（调用方传入的 discountAmt 被忽略）
                txReq = new ConfirmRequest(req.usageNo(), calc.discountAmt(), null, null);
            }
        }

        try {
            return tradeTxService.confirm(txReq, LocalDateTime.now(), split, lineShares);
        } catch (TradeCasRejectedException e) {
            // L2（2026-09-12）：券状态 CAS 拒绝且原因是"锁定期间过期"——
            // 先做收场（占用单过期关闭+券转已过期+事件），再按明确错误码拒绝。
            // 核销的守卫在 SQL 谓词里（valid_end > NOW()），这里的分类只是给准确的错误信息。
            if ("coupon-confirm".equals(e.getAction())) {
                CouponUsage usage = couponUsageRepository.findByUsageNo(req.usageNo()).orElse(null);
                if (usage != null) {
                    UserCoupon fresh = userCouponRepository.findById(usage.getUserCouponId()).orElse(null);
                    if (fresh != null && fresh.getStatus() == CouponStatus.LOCKED
                            && fresh.getValidEnd() != null && !fresh.getValidEnd().isAfter(LocalDateTime.now())) {
                        try {
                            tradeTxService.expireOnLock(req.usageNo(), fresh.getId(), LocalDateTime.now());
                        } catch (TradeCasRejectedException | BizException race) {
                            // 收场竞态（2026-10-15 二轮补）：另一请求已抢先收场（或占用单已非占用中）——
                            // 对外语义不变（券已过期、单已收场）；不把内部 CAS 信号漏出去
                            // （未分类的 TradeCasRejectedException 会掉进全局兜底变成 500）
                            log.warn("expireOnLock superseded by another actor: {}", race.getMessage());
                        }
                        throw new BizException(ErrorCode.COUPON_EXPIRED,
                                "锁定期间券已过期（订单号=" + usage.getOrderNo() + "），核销拒绝，占用单已过期关闭");
                    }
                }
            }
            throw freshStateConflict("核销", e);
        }
    }

    public ReleaseResult release(ReleaseRequest req) {
        assertUsageAccessible(req.usageNo());   // S1 越权防线
        try {
            return tradeTxService.release(req, LocalDateTime.now());
        } catch (TradeCasRejectedException e) {
            // 释放 CAS 失败（占用单或券状态竞争失败）——事务已整体回滚，按状态冲突报告
            throw freshStateConflict("释放", e);
        }
    }

    /**
     * 占用单的越权校验：占用单本身不带归属列（V14 只给模板与券加了 app_id），
     * 所以要从 usage → user_coupon 取一次归属。**这是刻意的取舍**：
     * 给分片的 coupon_usage 再加一列要动 64 张物理表，而核销/释放是低频操作，
     * 多一跳主键查询的代价远小于一次跨表 DDL 的风险。
     *
     * <p>占用单不存在时不在这里报错——交给原有逻辑报 4003，保持错误语义不变。
     */
    private void assertUsageAccessible(String usageNo) {
        couponUsageRepository.findByUsageNo(usageNo).ifPresent(usage ->
                userCouponRepository.findById(usage.getUserCouponId()).ifPresent(coupon ->
                        AppOwnership.assertAccessible(coupon.getAppId(), "usage:" + usageNo)));
    }

    /** 券包列表（status 可选过滤） */
    public MyCouponList myCoupons(long userId, CouponStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new MyCouponList(userCouponRepository.findByUser(userId, status, AppOwnership.currentAppId()).stream()
                .map(c -> new MyCoupon(c.getId(), c.getTemplateCode(), c.getFaceValue(),
                        c.getThreshold(), c.getDiscountCap(), c.getValidEnd(),
                        effectiveStatusDesc(c, now)))
                .toList());
    }

    /**
     * L2 惰性判断（2026-09-12）：状态列还是"待使用"但时间已过期的券，展示为"已过期"。
     * 只改显示不写库——查询是最高频操作，带写副作用会把读写的责任搅在一起；
     * 物理状态由过期任务扫描收敛（CouponExpireService），账本只由那一个入口改。
     */
    private String effectiveStatusDesc(UserCoupon c, LocalDateTime now) {
        if (c.getStatus() == CouponStatus.USABLE && c.getValidEnd() != null
                && !c.getValidEnd().isAfter(now)) {
            return CouponStatus.EXPIRED.getDesc();
        }
        return c.getStatus().getDesc();
    }

    // ---------- 内部 ----------

    /** 同一订单重复锁同一券的幂等出口：按 usageNo 查占用单（事务外新快照），
     *  仅"占用中"答"已锁定"——终态合同（F2）不能谎报，交由调用方走冲突分类 */
    private LockResult idempotentLock(LockRequest req) {
        // 与事务层使用同一个有界键：长 orderNo 会被折叠，快查/幂等重建必须用同一规则
        String usageNo = BoundedKey.usageNo(req.orderNo(), req.couponId());
        return couponUsageRepository.findByUsageNo(usageNo)
                .filter(u -> u.getStatus() == UsageStatus.OCCUPYING)   // F2：终态不回"已锁定"
                .map(u -> new LockResult(usageNo, req.couponId(), CouponStatus.LOCKED.getDesc(), true))
                .orElse(null);
    }

    /** CAS 失败的通用出口：把 CAS 信号拼成 4002"状态已被并发变更，请刷新后重试"。
     *  注意它本身不读取新快照——需要"读新快照给更准分类"的场景在各入口的 catch 里单独做
     *  （lock 的券状态分类、confirm 的"锁定期间过期"分支）；这里只是分类链的最后一格兜底。 */
    private BizException freshStateConflict(String action, TradeCasRejectedException e) {
        log.warn("{} cas rejected: {}", action, e.getMessage());
        return new BizException(ErrorCode.STATE_CONFLICT,
                action + "失败：状态已被并发变更（" + e.getMessage() + "），请刷新后重试");
    }

    /**
     * 出资拆分（第 2 环）：按模板的 budget_owner 计算平台/商家各自承担多少。
     * SPLIT 模式：platform = floor(discount × ratio / 100)，merchant = discount - platform（保证合计精确）。
     */
    /** 申报速率告警：固定 60s 窗口，按 appId 计数。只在"刚越过阈值"那一刻告警一次（防日志刷屏） */
    private void alarmConfirmRate() {
        String appId = RequestContext.current().appId();
        if (appId == null) {
            return;   // 内部调用路径无接入方身份（RequestContext.EMPTY），无速率可计
        }
        long now = System.currentTimeMillis();
        long[] window = confirmRateWindow.compute(appId, (k, old) -> {
            if (old == null || now - old[0] >= RATE_WINDOW_MS) {
                return new long[]{now, 1};
            }
            old[1]++;
            return old;
        });
        if (window[1] == confirmRateLimit + 1) {
            log.error("DEFENSE CONFIRM_RATE appId={} count={}/min exceeds limit={} ——疑似异常申报速率，"
                    + "接入方治理/风控介入（本防线只告警不拦截）", appId, window[1], confirmRateLimit);
        }
    }

    /**
     * 把占用单钉在 CONFIRM_EXCEPTION。标记失败只记 ERROR，不覆盖原始 4007——
     * 原始错误是调用方/运维最需要看到的现场。
     */
    /** 指标失败绝不能把一次已经成功的动钱操作变成 500 */
    private void safeMetric(Runnable metric) {
        try {
            metric.run();
        } catch (RuntimeException e) {
            log.warn("trade metric failed", e);
        }
    }

    private void markConfirmExceptionQuietly(String usageNo, String pricingId, String reason) {
        try {
            tradeTxService.markConfirmException(usageNo, pricingId, reason, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.error("mark confirm exception failed: usageNo={} pricingId={}", usageNo, pricingId, e);
        }
    }

    private static FundingSplit calculateFundingSplit(CouponTemplate template, long discountAmt) {
        return calculateFundingSplit(template.getBudgetOwner().name(),
                template.getBudgetRatio(), discountAmt);
    }

    /** 快照路径：出资方/比例全部来自冻结 payload，不读当前模板 */
    static FundingSplit calculateFundingSplit(String budgetOwner, Integer budgetRatio,
                                              long discountAmt) {
        BudgetOwner owner;
        try {
            owner = BudgetOwner.valueOf(budgetOwner);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PRICING_SNAPSHOT_INVALID, "价格快照出资方不合法");
        }
        return switch (owner) {
            case PLATFORM -> new FundingSplit(discountAmt, 0);
            case MERCHANT -> new FundingSplit(0, discountAmt);
            case SPLIT -> {
                if (budgetRatio == null || budgetRatio < 1 || budgetRatio > 99) {
                    throw new BizException(ErrorCode.PRICING_SNAPSHOT_INVALID,
                            "价格快照共担比例不合法");
                }
                long platform = discountAmt * budgetRatio / 100;
                yield new FundingSplit(platform, discountAmt - platform);
            }
        };
    }

    /**
     * 把总出资（平台/商家）拆到每一行，保证三条守恒：
     * ①每行平台+商家=该行优惠；②全体平台之和=总平台出资；③全体商家之和=总商家出资。
     *
     * <p>先按各行优惠额比例分平台出资（前 n-1 行取整、末行兜底，末行超行容量再回补），
     * 商家 = 行优惠 - 平台——这样既不会出现“某行商家为负”，也不会出现合计差一分。
     */
    static List<LineShare> buildLineShares(List<ConfirmLineShare> lines, FundingSplit split) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        long[] discounts = lines.stream().mapToLong(ConfirmLineShare::discountAmt).toArray();
        long[] platformShares = allocateFunding(split.platformBurden(), discounts);
        List<LineShare> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            ConfirmLineShare line = lines.get(i);
            long platform = platformShares[i];
            long merchant = line.discountAmt() - platform;
            result.add(new LineShare(line.lineNo(), line.skuId(), line.lineAmount(),
                    line.quantity(), line.discountAmt(), platform, merchant,
                    line.settlePartyId()));
        }
        return result;
    }

    /** 按容量比例分一笔钱：前 n-1 行向下取整、末行兜底；末行超容量则回补前面的行 */
    private static long[] allocateFunding(long amount, long[] capacities) {
        long[] shares = new long[capacities.length];
        long total = 0;
        for (long capacity : capacities) {
            total += capacity;
        }
        if (amount <= 0 || total <= 0) {
            return shares;
        }
        long capped = Math.min(amount, total);
        long allocated = 0;
        for (int i = 0; i < capacities.length - 1; i++) {
            shares[i] = capped * capacities[i] / total;
            allocated += shares[i];
        }
        int last = capacities.length - 1;
        long lastShare = capped - allocated;
        if (lastShare <= capacities[last]) {
            shares[last] = lastShare;
            return shares;
        }
        shares[last] = capacities[last];
        long overflow = lastShare - capacities[last];
        for (int i = 0; i < last && overflow > 0; i++) {
            long add = Math.min(capacities[i] - shares[i], overflow);
            shares[i] += add;
            overflow -= add;
        }
        return shares;
    }

    @AuditLog(action = "COUPON_REFUND", resourceType = "USAGE", describe = "退款冲销（动钱）")
    public RefundResult refund(RefundRequest req) {
        RefundResult result;
        try {
            result = doRefund(req);
        } catch (RuntimeException e) {
            safeMetric(() -> tradeMetrics.recordRefundFailed());
            throw e;
        }
        safeMetric(() -> tradeMetrics.recordRefund(result));
        return result;
    }

    private RefundResult doRefund(RefundRequest req) {
        assertUsageAccessible(req.usageNo());
        // 政策按“券的 appId”解析：内部面没有请求上下文，但券一定属于某个租户。
        CouponUsage usage = couponUsageRepository.findByUsageNo(req.usageNo())
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
        UserCoupon coupon = userCouponRepository.findById(usage.getUserCouponId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));
        RefundPolicy policy = refundPolicyService.resolve(coupon.getAppId());

        // 旧调用方不传退款单号：生成确定性的 AUTO:<usageNo>（只能整单退一次），
        // 新调用方必须传自己的退款单号，才能按行多次部分退款且可幂等重放。
        String refundNo = req.refundNo();
        if (refundNo == null || refundNo.isBlank()) {
            refundNo = BoundedKey.of("AUTO:", req.usageNo());
        }
        RefundRequest normalized = new RefundRequest(req.usageNo(), refundNo, req.items());
        try {
            return tradeTxService.refundConfirm(normalized, LocalDateTime.now(), policy);
        } catch (TradeCasRejectedException e) {
            throw freshStateConflict("退款", e);
        }
    }
}
