package com.newcoupon.application.service;

import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmResult;
import com.newcoupon.application.dto.TradeDtos.FundingSplit;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.MyCoupon;
import com.newcoupon.application.dto.TradeDtos.MyCouponList;
import com.newcoupon.application.dto.TradeDtos.ReleaseRequest;
import com.newcoupon.application.dto.TradeDtos.ReleaseResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.application.service.discount.DiscountCalculatorService;
import com.newcoupon.domain.coupon.BudgetOwner;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public TradeAppService(TradeTxService tradeTxService,
                           UserCouponRepository userCouponRepository,
                           CouponUsageRepository couponUsageRepository,
                                   DiscountCalculatorService discountCalculatorService) {
        this.tradeTxService = tradeTxService;
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.discountCalculatorService = discountCalculatorService;
    }

    public LockResult lock(LockRequest req) {
        RequestContext.current().user(req.userId());   // S1 治理插槽：内部面自动 no-op
        // S1 越权防线：先确认这张券属于调用方。查不到券时不在这里报错——交给下面的原有逻辑报 4001，
        // 免得把"券不存在"和"券不属于你"变成两条不同的早期分支
        userCouponRepository.findById(req.couponId())
                .ifPresent(c -> AppOwnership.assertAccessible(c.getAppId(), "coupon:" + c.getId()));
        // 幂等快查（与领券同构）：锁券的幂等证据在占用单（usageNo 唯一），不在券状态上——
        // 必须在进事务前先查，否则事务层对券状态的内存快检会先抛冲突（E2E 抓到的真实Bug）
        String usageNo = req.orderNo() + ":" + req.couponId();
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

        try {
            return tradeTxService.lock(req, LocalDateTime.now());
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

    public ConfirmResult confirm(ConfirmRequest req) {
        assertUsageAccessible(req.usageNo());   // S1 越权防线（占用单只带 usageNo，需经券取归属）

        // ════ 第 2 环：核销由平台算钱 + 出资拆分 ════
        FundingSplit split = null;
        ConfirmRequest txReq = req;   // 不重赋值参数 req（lambda 要求 effectively final）
        if (req.items() != null && !req.items().isEmpty()) {
            CouponUsage usage = couponUsageRepository.findByUsageNo(req.usageNo())
                    .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
            UserCoupon coupon = userCouponRepository.findById(usage.getUserCouponId())
                    .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));

            var calc = discountCalculatorService.calculateForConfirm(coupon, req.items());
            split = calculateFundingSplit(calc.template(), calc.discountAmt());

            // 用平台计算出的金额（调用方传入的 discountAmt 被忽略）
            txReq = new ConfirmRequest(req.usageNo(), calc.discountAmt(), null);
        }

        try {
            return tradeTxService.confirm(txReq, LocalDateTime.now(), split);
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
                        tradeTxService.expireOnLock(req.usageNo(), fresh.getId(), LocalDateTime.now());
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
        String usageNo = req.orderNo() + ":" + req.couponId();
        return couponUsageRepository.findByUsageNo(usageNo)
                .filter(u -> u.getStatus() == UsageStatus.OCCUPYING)   // F2：终态不回"已锁定"
                .map(u -> new LockResult(usageNo, req.couponId(), CouponStatus.LOCKED.getDesc(), true))
                .orElse(null);
    }

    /** CAS 失败的通用分类：新快照读占用单+券，给出带真实状态的错误信息 */
    private BizException freshStateConflict(String action, TradeCasRejectedException e) {
        log.warn("{} cas rejected: {}", action, e.getMessage());
        return new BizException(ErrorCode.STATE_CONFLICT,
                action + "失败：状态已被并发变更（" + e.getMessage() + "），请刷新后重试");
    }

    /**
     * 出资拆分（第 2 环）：按模板的 budget_owner 计算平台/商家各自承担多少。
     * SPLIT 模式：platform = floor(discount × ratio / 100)，merchant = discount - platform（保证合计精确）。
     */
    private FundingSplit calculateFundingSplit(CouponTemplate template, long discountAmt) {
        return switch (template.getBudgetOwner()) {
            case PLATFORM -> new FundingSplit(discountAmt, 0);
            case MERCHANT -> new FundingSplit(0, discountAmt);
            case SPLIT -> {
                long platform = discountAmt * template.getBudgetRatio() / 100;
                yield new FundingSplit(platform, discountAmt - platform);
            }
        };
    }
}
