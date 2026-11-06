package com.newcoupon.application.service;

import com.newcoupon.application.dto.TradeDtos.RefundItem;
import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.dto.TradeDtos.RefundResult;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmResult;
import com.newcoupon.application.dto.TradeDtos.FundingSplit;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.ReleaseRequest;
import com.newcoupon.application.dto.TradeDtos.ReleaseResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.pricing.PricingUsage;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.PricingSnapshotRepository;
import com.newcoupon.domain.repository.PricingUsageRepository;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.domain.settle.SettleQualityFlags;
import com.newcoupon.domain.statemachine.CouponStateMachine;
import com.newcoupon.domain.statemachine.CouponTransition;
import com.newcoupon.infrastructure.persistence.CouponReleaseTaskGateway;
import com.newcoupon.infrastructure.persistence.mapper.CouponConfirmExceptionMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundDetailMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundOrderMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponReturnLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
import com.newcoupon.infrastructure.persistence.po.CouponConfirmExceptionPO;
import com.newcoupon.infrastructure.persistence.po.CouponRefundDetailPO;
import com.newcoupon.infrastructure.persistence.po.CouponRefundOrderPO;
import com.newcoupon.infrastructure.persistence.po.CouponReturnLogPO;
import com.newcoupon.infrastructure.persistence.po.CouponShareDetailPO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用券事务服务（结构纪律与 CouponIssueTxService 相同：独立类防自调用失效；
 * 编排层严禁 @Transactional 防 rollback-only 退化——见 study/04a 第 4 部分）。
 *
 * <p>写入清单（每段各自一个事务）：
 * <pre>
 * 锁券 lock    = UPDATE 券状态(0→1) + INSERT 占用单 + INSERT 锁定事件 + INSERT 超时任务(L5)
 * 核销 confirm = UPDATE 占用单(0→1,含金额) + UPDATE 券状态(1→2) + INSERT 核销事件
 *                + INSERT 行级分账（一行商品一条；老式核销一行 NULL）
 * 释放 release = UPDATE 占用单(0→2) + UPDATE 券状态(1→0/3) + INSERT 释放事件
 * 退款 refund  = INSERT 退款单 + INSERT 退款明细（按行） + UPDATE 占用单(1/6→6/5)
 *                + 整单退时 UPDATE 券(2→0/3)+INSERT 退券留痕 + INSERT 退款事件
 * </pre>
 *
 * <p>L5（2026-09-15）：lock 同事务注册锁定超时任务（"锁了必有兜底"）；
 * 新增 timeoutClose 事务方法（扫描器路径的超时收场）——三件事一个事务，
 * 对照 F1 的教训（expire 扫描器曾把收场两条 UPDATE 拆在事务外，崩溃留半截）。
 *
 * <p>CAS 0 行 → 抛 TradeCasRejectedException（事务回滚），由编排层新快照分类。
 */
@Service
public class TradeTxService {

    private static final Logger log = LoggerFactory.getLogger(TradeTxService.class);

    /** 分账默认主体（结算主体开关关/未传时的归集口径）；TradeAppService 退款冻结同值引用 */
    static final String DEFAULT_PARTY = "__default__";

    private final UserCouponRepository userCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CouponEventRepository couponEventRepository;
    private final CouponShareDetailMapper couponShareDetailMapper;
    private final CouponRefundOrderMapper couponRefundOrderMapper;
    private final CouponRefundDetailMapper couponRefundDetailMapper;
    private final CouponReturnLogMapper couponReturnLogMapper;
    private final CouponConfirmExceptionMapper couponConfirmExceptionMapper;
    private final PricingSnapshotRepository pricingSnapshotRepository;
    private final PricingUsageRepository pricingUsageRepository;
    private final CouponReleaseTaskGateway releaseTaskGateway;
    /** 资金边界守卫（V6 2B）：退款入口拒绝沙箱租户 */
    private final EnvironmentGuard environmentGuard;
    /** 质量打标（V47）要读模板出资方：直读不走缓存——funder_type 创建后不可变，但保持
     *  事务层"读什么就信什么"的朴素（缓存纪律见 CachedTemplateReader 注释，不扩大其服务面） */
    private final TemplateRepository templateRepository;
    private final long lockTimeoutMinutes;

    public TradeTxService(UserCouponRepository userCouponRepository,
                          CouponUsageRepository couponUsageRepository,
                          CouponEventRepository couponEventRepository,
                          CouponShareDetailMapper couponShareDetailMapper,
                          CouponRefundOrderMapper couponRefundOrderMapper,
                          CouponRefundDetailMapper couponRefundDetailMapper,
                          CouponReturnLogMapper couponReturnLogMapper,
                          CouponConfirmExceptionMapper couponConfirmExceptionMapper,
                          PricingSnapshotRepository pricingSnapshotRepository,
                          PricingUsageRepository pricingUsageRepository,
                          CouponReleaseTaskGateway releaseTaskGateway,
                          EnvironmentGuard environmentGuard,
                          TemplateRepository templateRepository,
                          @Value("${coupon.trade.lock-timeout-minutes:30}") long lockTimeoutMinutes) {
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.couponEventRepository = couponEventRepository;
        this.couponShareDetailMapper = couponShareDetailMapper;
        this.couponRefundOrderMapper = couponRefundOrderMapper;
        this.couponRefundDetailMapper = couponRefundDetailMapper;
        this.couponReturnLogMapper = couponReturnLogMapper;
        this.couponConfirmExceptionMapper = couponConfirmExceptionMapper;
        this.pricingSnapshotRepository = pricingSnapshotRepository;
        this.environmentGuard = environmentGuard;
        this.pricingUsageRepository = pricingUsageRepository;
        this.releaseTaskGateway = releaseTaskGateway;
        this.templateRepository = templateRepository;
        this.lockTimeoutMinutes = lockTimeoutMinutes;
    }

    @Transactional
    public LockResult lock(LockRequest req, LocalDateTime now) {
        return lock(req, now, null);
    }

    /**
     * 锁券事务（环 2）：带 {@code pricingId} 时在同一事务里续期快照并写 usage 绑定。
     * 续期失败（快照已过期/撤销）→ 4007 且整个锁券回滚，不会出现“券锁了但价格没冻住”。
     */
    @Transactional
    public LockResult lock(LockRequest req, LocalDateTime now, String pricingId) {
        UserCoupon coupon = userCouponRepository.findByIdForUser(req.couponId(), req.userId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND, "id=" + req.couponId()));
        if (coupon.getUserId() != req.userId()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "券不属于该用户（防拿他人券下单）");
        }
        CouponStateMachine.transit(coupon.getStatus(), CouponTransition.LOCK);   // 内存快检：USABLE→LOCKED
        if (!userCouponRepository.casLock(req.couponId(), req.userId())) {
            throw new TradeCasRejectedException("coupon-lock", req.couponId());
        }
        String usageNo = com.newcoupon.common.BoundedKey.usageNo(req.orderNo(), req.couponId());
        CouponUsage usage = couponUsageRepository.save(
                CouponUsage.occupy(coupon, req.orderNo(), usageNo, now));        // 撞uk_usage_no→DuplicateKey
        if (pricingId != null && !pricingId.isBlank()) {
            // 快照续期到“锁窗 + 10 分钟宽限”：试算后第 29 分钟锁券，支付回调也不会
            // 在锁窗内因快照先过期而失败。0 行 = 快照已过期/撤销 → 整个事务回滚。
            LocalDateTime minExpiresAt = now.plusMinutes(lockTimeoutMinutes + 10);
            if (!pricingSnapshotRepository.renewOnLock(pricingId, minExpiresAt, now)) {
                throw new BizException(ErrorCode.PRICING_SNAPSHOT_INVALID,
                        "价格快照已过期或已失效");
            }
            pricingUsageRepository.save(new PricingUsage(null, usageNo, pricingId,
                    coupon.getAppId(), coupon.getUserId(), req.orderNo(), coupon.getId(), now));
        }
        couponEventRepository.append(CouponEvent.locked(coupon, usage, now));
        // L5：注册锁定超时任务（同一事务——"锁了必有兜底"；deadline=占用时间+窗口，
        // 测试用 coupon.trade.lock-timeout-minutes=0 使任务即刻到期）
        releaseTaskGateway.register(usageNo, coupon.getId(), req.userId(),
                now.plusMinutes(lockTimeoutMinutes));
        return new LockResult(usageNo, req.couponId(), CouponStatus.LOCKED.getDesc(), false);
    }

    @Transactional
    public ConfirmResult confirm(ConfirmRequest req, LocalDateTime now, FundingSplit split) {
        return doConfirm(req, null, now, split, java.util.List.of(), null);
    }

    /**
     * 核销事务：占用单/券状态 CAS + 事件 + 行级分账落表，全在一个事务里。
     *
     * @param lineShares 行级分账（2026-10-18 行级版）。空列表 = 有 split 但无行级分摊的兼容路径，
     *                   写一条订单粒度的分账行（order_line_no 为 NULL）；
     *                   老式核销 split=null，不写任何分账行（日账单不可见，见 STATUS L68）。
     */
    @Transactional
    public ConfirmResult confirm(ConfirmRequest req, LocalDateTime now, FundingSplit split,
                                 java.util.List<com.newcoupon.application.dto.TradeDtos.LineShare> lineShares) {
        return doConfirm(req, null, now, split, lineShares, null);
    }

    /**
     * 编排层已经通过入口查询拿到了占用单的 userId：按 (usageNo, userId) 单分片读，
     * 不再让事务层重复广播 64 张物理表。userId 必须是数据库里那一行的值，
     * 不能直接使用外部请求自报的 userId（签名只认证 appId）。
     */
    @Transactional
    public ConfirmResult confirm(ConfirmRequest req, long userId, LocalDateTime now, FundingSplit split,
                                 java.util.List<com.newcoupon.application.dto.TradeDtos.LineShare> lineShares) {
        return doConfirm(req, userId, now, split, lineShares, null);
    }

    /**
     * L75 版本：多带一个“核销金额所依据的订单券集合”。
     *
     * <p>事务会先锁住同订单全部占用单，再把集合与当下的生效集合比对——事务外
     * prepareConfirm / L64 复算之后、本事务开始之前，兄弟券可能已被释放或退款；
     * 只信事务外那份旧集合就会按失效报价核销。expectedCouponIds=null 表示调用方
     * 没有订单级集合可校验（纯旧式金额核销），仍会做售后态与目标单状态重校验。
     */
    @Transactional
    public ConfirmResult confirm(ConfirmRequest req, long userId, LocalDateTime now, FundingSplit split,
                                 java.util.List<com.newcoupon.application.dto.TradeDtos.LineShare> lineShares,
                                 Set<Long> expectedCouponIds) {
        return doConfirm(req, userId, now, split, lineShares, expectedCouponIds);
    }

    private ConfirmResult doConfirm(ConfirmRequest req, Long userId, LocalDateTime now, FundingSplit split,
                                    java.util.List<com.newcoupon.application.dto.TradeDtos.LineShare> lineShares,
                                    Set<Long> expectedCouponIds) {
        CouponUsage snapshot = readUsage(req.usageNo(), userId)
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
        // L75（TOCTOU）：交易外校验通过 → 本事务开始之间仍有窗口，兄弟券可能被释放/退款。
        // 先按 id 升序锁住同订单全部占用单，之后读到的状态才是权威值；校验不过就整体拒绝，
        // 绝不让一条基于失效券集合的报价落账。
        List<CouponUsage> orderUsages = lockOrderUsages(snapshot);
        CouponUsage usage = orderUsages.stream()
                .filter(u -> u.getUsageNo().equals(req.usageNo()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
        if (usage.getStatus() == UsageStatus.CONFIRMED) {
            // 幂等优先：支付回调重复推送，第一次已核销——直接当成功返回。
            // 这一步放在券集合/售后校验之前：已成功的核销即使后来兄弟券退款，
            // 重放也必须拿回首次结果，不能被新的拒绝语义误伤。
            return new ConfirmResult(req.usageNo(), CouponStatus.USED.getDesc(),
                    usage.getDiscountAmt(), true);
        }
        usage.checkConfirmable();   // RELEASED/REFUNDED/EXPIRED/超时 → 拒绝
        UserCoupon coupon = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));
        // L75 审查 C5：coupon_usage 没有 app_id，同 user_id+order_no 可能是两个租户的
        // 独立订单（分片键只有 user_id，落在同一张物理表）。只有与目标券同租户的
        // 占用单才算本单的兄弟券，否则 A 租户的退款会误杀 B 租户的旧式核销。
        List<CouponUsage> tenantUsages = sameTenantUsages(orderUsages, usage, coupon.getAppId());
        assertNoAfterSales(tenantUsages);   // L75：锁内重校验售后态
        // 普通核销的生效集合=占用中+已核销（CONFIRM_EXCEPTION 不算，与 L70 口径一致）
        assertExpectedCouponSet(tenantUsages, expectedCouponIds, req.pricingId() != null, false);
        if (!couponUsageRepository.casConfirm(req.usageNo(), usage.getUserId(), req.discountAmt(), now)) {
            throw new TradeCasRejectedException("usage-confirm", req.usageNo());
        }
        if (!userCouponRepository.casConfirm(usage.getUserCouponId(), usage.getUserId())) {
            throw new TradeCasRejectedException("coupon-confirm", usage.getUserCouponId());
        }
        couponEventRepository.append(CouponEvent.used(coupon, usage, req.discountAmt(), now));

        // ════ 第 2 环：出资拆分落表（与核销同一事务——原子）════
        if (split != null) {
            // 质量打标（V47）要判"商家/共担出资的钱是否落 __default__ 主体"——出资方在同一事务里
            // 读一次模板主数据（funder_type 创建后不可变），下面两条插入路径共用这一份判定。
            FundingQuality funding = resolveFundingQuality(coupon.getTemplateId());
            if (lineShares != null && !lineShares.isEmpty()) {
                insertLineShares(req, coupon, usage, split, lineShares, funding);
            } else {
                insertOrderLevelShare(req, coupon, usage, split, funding);
            }
        }

        return new ConfirmResult(req.usageNo(), CouponStatus.USED.getDesc(), req.discountAmt(), false);
    }

    /**
     * 按 id 升序读出并锁住同一订单的全部占用单（L75）。
     *
     * <p>同订单的两条核销可能同时发生，双方都按 id 升序申请行锁，不会出现
     * “各自握着一行、再等对方的行”的交叉等待。返回的是锁后读到的最新已提交状态——
     * 事务开始时那次普通读只用于拿路由键（userId/orderNo），不能拿来判状态。
     */
    private List<CouponUsage> lockOrderUsages(CouponUsage usage) {
        return couponUsageRepository.findByOrderNoForUpdate(usage.getUserId(), usage.getOrderNo());
    }

    /**
     * 只保留与目标券同租户的占用单（L75 审查 C5）。
     *
     * <p>{@code coupon_usage} 没有 {@code app_id} 列，分片键只有 {@code user_id}——
     * 两个租户各自命名空间里的同一个 userId 用了同一个订单号时，两边的占用单会落在
     * 同一张物理分表并被同一条订单锁查询捞出来。目标券那一行直接采信；其余行按
     * {@code user_coupon.app_id} 过滤。兄弟券数量=叠加层数（个位数），
     * 这里用点查换正确性，不做跨表 JOIN。
     */
    private List<CouponUsage> sameTenantUsages(List<CouponUsage> orderUsages,
                                               CouponUsage target, String appId) {
        List<CouponUsage> same = new ArrayList<>(orderUsages.size());
        for (CouponUsage u : orderUsages) {
            if (u.getUsageNo().equals(target.getUsageNo())) {
                same.add(u);
                continue;
            }
            UserCoupon sibling = userCouponRepository
                    .findByIdForUser(u.getUserCouponId(), u.getUserId())
                    .orElse(null);
            if (sibling != null && appId.equals(sibling.getAppId())) {
                same.add(u);
            }
        }
        return same;
    }

    /**
     * 售后态重校验（L75）：同订单只要有一张券处于退款中/已退款，就拒绝核销其他券。
     * 事务外的同名校验保留（快速失败、少拿锁），这里是拿锁后的权威判定。
     */
    private static void assertNoAfterSales(List<CouponUsage> orderUsages) {
        boolean afterSales = orderUsages.stream().anyMatch(u ->
                u.getStatus() == UsageStatus.REFUNDED
                        || u.getStatus() == UsageStatus.PARTIALLY_REFUNDED);
        if (afterSales) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "订单已进入售后（部分/全部退款），不能再核销其他券");
        }
    }

    /**
     * 券集合重校验（L75）：锁内重新计算订单的“生效券集合”，与事务外算钱时使用的集合比对。
     *
     * <p>生效集合 = 占用中 + 已核销（与 L70 口径一致；已核销的兄弟券仍然算本单
     * 已使用的优惠，不能从集合里消失）。管理面核销异常单时目标单是
     * CONFIRM_EXCEPTION，调用方按 includeExceptionStatus 口径传入的集合会包含它，
     * 因此这里由调用方决定状态口径，不硬编码。
     *
     * @param expectedCouponIds      算钱时使用的券集合；null = 纯旧式金额核销，无集合可校验
     * @param snapshotBacked         true=集合来自价格快照（不符按 4007 fail-closed），
     *                               false=集合来自 L64 订单级复算（不符按 4002 状态冲突，可重试）
     * @param includeExceptionStatus 人工核销异常单时为 true——目标单正处于
     *                               CONFIRM_EXCEPTION，集合口径必须包含它
     */
    private static void assertExpectedCouponSet(List<CouponUsage> orderUsages,
                                                Set<Long> expectedCouponIds,
                                                boolean snapshotBacked,
                                                boolean includeExceptionStatus) {
        if (expectedCouponIds == null) {
            return;
        }
        Set<Long> active = new HashSet<>();
        for (CouponUsage u : orderUsages) {
            if (u.getStatus() == UsageStatus.OCCUPYING
                    || u.getStatus() == UsageStatus.CONFIRMED
                    || (includeExceptionStatus && u.getStatus() == UsageStatus.CONFIRM_EXCEPTION)) {
                active.add(u.getUserCouponId());
            }
        }
        if (active.equals(expectedCouponIds)) {
            return;
        }
        if (snapshotBacked) {
            throw new BizException(ErrorCode.PRICING_SNAPSHOT_INVALID,
                    "价格快照券集合与订单占用单不一致（核销期间订单券集合发生变化）: 快照="
                            + expectedCouponIds + " 订单=" + active);
        }
        throw new BizException(ErrorCode.STATE_CONFLICT,
                "订单占用单集合在核销计算后发生变化，请重新试算后重试: 计算用="
                        + expectedCouponIds + " 当前=" + active);
    }

    /** 订单粒度分账（有 split 但没有行级分摊时的兼容路径：没有行号/行金额/行数量） */
    private void insertOrderLevelShare(ConfirmRequest req, UserCoupon coupon, CouponUsage usage,
                                       FundingSplit split, FundingQuality funding) {
        CouponShareDetailPO share = new CouponShareDetailPO();
        share.setUserCouponId(coupon.getId());
        share.setUserId(coupon.getUserId());
        share.setTemplateId(coupon.getTemplateId());
        share.setAppId(coupon.getAppId());
        share.setSettlePartyId(DEFAULT_PARTY);
        share.setPricingId(req.pricingId());
        share.setUsageNo(req.usageNo());
        share.setOrderNo(usage.getOrderNo());
        share.setPlatformBurden(split.platformBurden());
        share.setMerchantBurden(split.merchantBurden());
        share.setShareTotal(split.total());
        // 订单级行没有行号 → NO_ITEMS（与 coupon_refund_order.funding_known=0 同族的粗粒度标记）；
        // 出资拆分是平台算的真实值，只降观测粒度、不排除出账单
        ShareQuality quality = shareQuality(funding, DEFAULT_PARTY, true);
        share.setQualityFlags(quality.flags());
        share.setSettleEligible(quality.eligible() ? 1 : 0);
        couponShareDetailMapper.insert(share);
    }

    /**
     * 行级分账：一行商品一条记录。落库前做三条守恒校验——任何一条不成立都抛异常回滚，
     * 绝不让“行分摊合计 ≠ 券优惠”的账落进库（这是后面按行退款的唯一依据）。
     */
    private void insertLineShares(ConfirmRequest req, UserCoupon coupon, CouponUsage usage,
                                  FundingSplit split,
                                  java.util.List<com.newcoupon.application.dto.TradeDtos.LineShare> lineShares,
                                  FundingQuality funding) {
        long sumDiscount = lineShares.stream()
                .mapToLong(com.newcoupon.application.dto.TradeDtos.LineShare::discountAmt).sum();
        long sumPlatform = lineShares.stream()
                .mapToLong(com.newcoupon.application.dto.TradeDtos.LineShare::platformBurden).sum();
        long sumMerchant = lineShares.stream()
                .mapToLong(com.newcoupon.application.dto.TradeDtos.LineShare::merchantBurden).sum();
        if (sumDiscount != req.discountAmt()
                || sumPlatform != split.platformBurden()
                || sumMerchant != split.merchantBurden()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, String.format(
                    "行级分账守恒校验失败：行优惠合计=%d（券优惠=%d），平台=%d（应=%d），商家=%d（应=%d）",
                    sumDiscount, req.discountAmt(), sumPlatform, split.platformBurden(),
                    sumMerchant, split.merchantBurden()));
        }

        for (com.newcoupon.application.dto.TradeDtos.LineShare line : lineShares) {
            CouponShareDetailPO share = new CouponShareDetailPO();
            share.setUserCouponId(coupon.getId());
            share.setUserId(coupon.getUserId());
            share.setTemplateId(coupon.getTemplateId());
            share.setAppId(coupon.getAppId());
            String party = line.settlePartyId() == null ? DEFAULT_PARTY : line.settlePartyId();
            share.setSettlePartyId(party);
            share.setPricingId(req.pricingId());
            share.setUsageNo(req.usageNo());
            share.setOrderNo(usage.getOrderNo());
            share.setOrderLineNo(line.lineNo());
            share.setOrderLineAmount(line.lineAmount());
            share.setOrderLineQuantity(line.quantity());
            share.setPlatformBurden(line.platformBurden());
            share.setMerchantBurden(line.merchantBurden());
            share.setShareTotal(line.discountAmt());
            // 主体逐行不同（灰度开时一行 A 一行 __default__ 都可能），判定必须放进循环里
            ShareQuality quality = shareQuality(funding, party, false);
            share.setQualityFlags(quality.flags());
            share.setSettleEligible(quality.eligible() ? 1 : 0);
            couponShareDetailMapper.insert(share);
        }
    }

    /**
     * 分账行质量打标（V47，与迁移回填同口径——两处判定必须一致，见 SettleBackfillSql）。
     *
     * <p>排除规则只有两条：①模板主数据缺失（ORPHAN_TEMPLATE——归属没法判，宁可多排除进
     * 人工复核，不把归属不明的行算进可结算桶；正常不可达，核销前链路都要求模板存在）；
     * ②商家/共担出资的钱落在 {@code __default__}（灰度关期间的写入/行缺主体）→ LEGACY_DEFAULT
     * ——这钱算不清该和哪个商家结算。平台券 {@code __default__} 是合法空主体（平台和自己结算），
     * 不打标不排除。订单级兼容行加 NO_ITEMS：出资拆分真实，只降观测粒度。
     */
    private ShareQuality shareQuality(FundingQuality funding, String party, boolean orderLevel) {
        int flags = 0;
        boolean eligible = true;
        if (funding.orphan()) {
            flags |= SettleQualityFlags.ORPHAN_TEMPLATE;
            eligible = false;
        } else if (funding.merchantFunded() && DEFAULT_PARTY.equals(party)) {
            flags |= SettleQualityFlags.LEGACY_DEFAULT;
            eligible = false;
        }
        if (orderLevel) {
            flags |= SettleQualityFlags.NO_ITEMS;
        }
        return new ShareQuality(flags, eligible);
    }

    /** 分账行质量判定结果（flags=位掩码；eligible=是否进真实结算口径） */
    private record ShareQuality(int flags, boolean eligible) {
    }

    /** 模板出资方判定（一次核销读一次，两条插入路径共用）：merchantFunded=商家/共担；orphan=模板缺失 */
    private record FundingQuality(boolean merchantFunded, boolean orphan) {
    }

    /** 读模板主数据判出资方（funder_type 创建后不可变；直读不走缓存，理由见字段注释） */
    private FundingQuality resolveFundingQuality(long templateId) {
        return templateRepository.findById(templateId)
                .map(t -> new FundingQuality(
                        t.getBudgetOwner() == com.newcoupon.domain.coupon.BudgetOwner.MERCHANT
                                || t.getBudgetOwner() == com.newcoupon.domain.coupon.BudgetOwner.SPLIT,
                        false))
                .orElse(new FundingQuality(false, true));
    }

    // ════ 环 2：支付后核销异常（CONFIRM_EXCEPTION）════

    /**
     * 占用中 → 核销异常（独立事务）。用户已支付但快照校验失败时调用：
     * 占用单进入 7、券保持 LOCKED、记异常行与事件；30 分钟超时任务看到非 0 状态会 no-op，
     * 因此不会把券放回 USABLE（否则“已付款 + 券回可用”= 双重优惠）。
     *
     * @return true=本次进入异常；false=占用单已不是占用中（已被核销/释放/超时收场），不覆盖
     */
    @Transactional
    public boolean markConfirmException(String usageNo, String pricingId, String reason,
                                        LocalDateTime now) {
        return doMarkConfirmException(usageNo, null, pricingId, reason, now);
    }

    /** 编排层已持有 usage.userId：单分片读；参数语义同上面的兼容入口。 */
    @Transactional
    public boolean markConfirmException(String usageNo, long userId, String pricingId, String reason,
                                        LocalDateTime now) {
        return doMarkConfirmException(usageNo, userId, pricingId, reason, now);
    }

    private boolean doMarkConfirmException(String usageNo, Long userId, String pricingId,
                                           String reason, LocalDateTime now) {
        CouponUsage usage = readUsage(usageNo, userId).orElse(null);
        if (usage == null || usage.getStatus() != UsageStatus.OCCUPYING) {
            return false;
        }
        if (!couponUsageRepository.casConfirmException(usageNo, usage.getUserId())) {
            return false;
        }
        UserCoupon coupon = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId()).orElse(null);
        if (coupon != null) {
            couponEventRepository.append(CouponEvent.confirmException(coupon, usage, now));
        }
        // 异常行只记录“锁券时绑定的快照”，不记录核销请求临时携带的 ID：
        // 否则人工 CONFIRM 会拿一个未绑定的报价绕过锁券契约。没有绑定就存 null，
        // 人工只能核对订单后 RELEASE。
        String boundPricingId = pricingUsageRepository.findByUsageNo(usageNo)
                .map(PricingUsage::pricingId)
                .orElse(null);
        CouponConfirmExceptionPO row = new CouponConfirmExceptionPO();
        row.setUsageNo(usageNo);
        // app_id 列 NOT NULL：券极端缺失时落内置应用，保证异常记录能落库（宁可归属不准也要留住现场）
        row.setAppId(coupon == null
                ? com.newcoupon.domain.open.OpenApp.BUILTIN_APP_ID : coupon.getAppId());
        row.setUserId(usage.getUserId());
        row.setUserCouponId(usage.getUserCouponId());
        row.setOrderNo(usage.getOrderNo());
        row.setPricingId(boundPricingId);
        row.setReason(truncate(reason, 512));
        row.setResolveStatus("PENDING");
        row.setCreateTime(now);
        couponConfirmExceptionMapper.insert(row);
        log.error("PRICING_CONFIRM_EXCEPTION usageNo={} pricingId={} reason={}",
                usageNo, pricingId, reason);
        return true;
    }

    /**
     * 人工按快照原价核销异常单（管理面）：占用单 7→1、券 LOCKED→USED、
     * 落行级分账（带 pricingId）与异常处理留痕。券若已被过期任务收成 EXPIRED，
     * 拒绝核销——此时只能确认退款后 RELEASE。
     */
    @Transactional
    public ConfirmResult confirmFromException(String usageNo, long discountAmt, FundingSplit split,
                                              java.util.List<com.newcoupon.application.dto.TradeDtos.LineShare> lineShares,
                                              String pricingId, String resolveReason,
                                              LocalDateTime now) {
        return doConfirmFromException(usageNo, discountAmt, split, lineShares, pricingId,
                resolveReason, now, null);
    }

    /**
     * L75 版本：管理面人工核销也带“快照券集合”，在事务内锁后重校验。
     * 该路径同样先过 prepareConfirm（ConfirmExceptionService），所以窗口同样存在。
     */
    @Transactional
    public ConfirmResult confirmFromException(String usageNo, long discountAmt, FundingSplit split,
                                              java.util.List<com.newcoupon.application.dto.TradeDtos.LineShare> lineShares,
                                              String pricingId, String resolveReason,
                                              LocalDateTime now, Set<Long> expectedCouponIds) {
        return doConfirmFromException(usageNo, discountAmt, split, lineShares, pricingId,
                resolveReason, now, expectedCouponIds);
    }

    private ConfirmResult doConfirmFromException(String usageNo, long discountAmt, FundingSplit split,
                                                 java.util.List<com.newcoupon.application.dto.TradeDtos.LineShare> lineShares,
                                                 String pricingId, String resolveReason,
                                                 LocalDateTime now, Set<Long> expectedCouponIds) {
        CouponUsage snapshot = couponUsageRepository.findByUsageNo(usageNo)
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, usageNo));
        // L75：与普通核销同一条锁序——先按 id 升序锁住同订单占用单，再重校验。
        // 人工核销与用户核销可能并发；锁内读到的售后态/券集合才是权威值。
        List<CouponUsage> orderUsages = lockOrderUsages(snapshot);
        CouponUsage usage = orderUsages.stream()
                .filter(u -> u.getUsageNo().equals(usageNo))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, usageNo));
        if (usage.getStatus() != UsageStatus.CONFIRM_EXCEPTION) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "占用单状态=" + usage.getStatus().getDesc() + "，仅核销异常可人工核销");
        }
        UserCoupon coupon = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));
        // C5：同 user+orderNo 可能跨租户共存，售后/集合判定只认同租户的占用单
        List<CouponUsage> tenantUsages = sameTenantUsages(orderUsages, usage, coupon.getAppId());
        assertNoAfterSales(tenantUsages);
        // 人工核销的目标单正处于 CONFIRM_EXCEPTION，生效集合口径包含该状态
        assertExpectedCouponSet(tenantUsages, expectedCouponIds, true, true);
        if (!couponUsageRepository.casConfirmFromException(usageNo, usage.getUserId(), discountAmt, now)) {
            throw new TradeCasRejectedException("usage-confirm-exception", usageNo);
        }
        if (coupon.getStatus() != CouponStatus.LOCKED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "券状态=" + coupon.getStatus().getDesc()
                            + "，已不能按快照核销；请确认订单退款后选择 RELEASE");
        }
        CouponStateMachine.transit(coupon.getStatus(), CouponTransition.CONFIRM);
        if (!userCouponRepository.casConfirm(usage.getUserCouponId(), usage.getUserId())) {
            throw new TradeCasRejectedException("coupon-confirm-exception", usage.getUserCouponId());
        }
        couponEventRepository.append(CouponEvent.used(coupon, usage, discountAmt, now));
        if (split != null) {
            ConfirmRequest shareReq = new ConfirmRequest(usageNo, discountAmt, null, pricingId);
            FundingQuality funding = resolveFundingQuality(coupon.getTemplateId());
            if (lineShares != null && !lineShares.isEmpty()) {
                insertLineShares(shareReq, coupon, usage, split, lineShares, funding);
            } else {
                insertOrderLevelShare(shareReq, coupon, usage, split, funding);
            }
        }
        couponConfirmExceptionMapper.markResolved(usageNo, "CONFIRMED",
                truncate(resolveReason, 512), now);
        return new ConfirmResult(usageNo, CouponStatus.USED.getDesc(), discountAmt, false);
    }

    /**
     * 人工释放异常单（管理面）：仅用于“订单已退款/不会使用该优惠”的核实结论。
     * 占用单 7→2；券 LOCKED 时回 USABLE（已过期则保持 EXPIRED），绝不把已过期券改回可用。
     */
    @Transactional
    public ReleaseResult releaseFromException(String usageNo, String resolveReason, LocalDateTime now) {
        CouponUsage usage = couponUsageRepository.findByUsageNo(usageNo)
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, usageNo));
        if (usage.getStatus() != UsageStatus.CONFIRM_EXCEPTION) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "占用单状态=" + usage.getStatus().getDesc() + "，仅核销异常可人工释放");
        }
        if (!couponUsageRepository.casReleaseFromException(usageNo, usage.getUserId(), now)) {
            throw new TradeCasRejectedException("usage-release-exception", usageNo);
        }
        UserCoupon coupon = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));
        if (coupon.getStatus() == CouponStatus.LOCKED) {
            CouponStateMachine.transit(coupon.getStatus(), CouponTransition.RELEASE);
            if (!userCouponRepository.casRelease(usage.getUserCouponId(), usage.getUserId())) {
                throw new TradeCasRejectedException("coupon-release-exception",
                        usage.getUserCouponId());
            }
        } else if (coupon.getStatus() != CouponStatus.EXPIRED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "券状态=" + coupon.getStatus().getDesc() + "，不能人工释放");
        }
        couponEventRepository.append(CouponEvent.released(coupon, usage, now));
        couponConfirmExceptionMapper.markResolved(usageNo, "RELEASED",
                truncate(resolveReason, 512), now);
        UserCoupon fresh = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId()).orElse(coupon);
        return new ReleaseResult(usageNo, fresh.getStatus().getDesc(), false);
    }

    /**
     * 占用单读取的唯一分派点：有可信 userId 走单分片，没有才保留一次广播。
     * 外部入口只有 usageNo，第一次广播不可避免；拿到 userId 后，
     * 事务层、错误收场和重放都不应再广播。
     */
    private java.util.Optional<CouponUsage> readUsage(String usageNo, Long userId) {
        return userId == null
                ? couponUsageRepository.findByUsageNo(usageNo)
                : couponUsageRepository.findByUsageNo(usageNo, userId);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 锁定中过期的收场（L2，2026-09-12）：核销因"锁定期间券到期"被拒后，由编排层调用。
     * 三件事一个事务：占用单 占用中→过期关闭（第三种终结，非核销非释放）、
     * 券 已锁定→已过期（终态）、落 EXPIRED 事件（含订单号，供对账归因）。
     * 补贴不承担——过期券不付钱。
     */
    @Transactional
    public void expireOnLock(String usageNo, long couponId, LocalDateTime now) {
        doExpireOnLock(usageNo, couponId, null, now);
    }

    /** 编排层已持有 usage.userId：单分片读；参数语义同兼容入口。 */
    @Transactional
    public void expireOnLock(String usageNo, long couponId, long userId, LocalDateTime now) {
        doExpireOnLock(usageNo, couponId, userId, now);
    }

    private void doExpireOnLock(String usageNo, long couponId, Long userId, LocalDateTime now) {
        CouponUsage usage = readUsage(usageNo, userId)
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, usageNo));
        if (usage.getStatus() != UsageStatus.OCCUPYING) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "占用单状态=" + usage.getStatus().getDesc() + "，仅占用中可过期关闭");
        }
        UserCoupon coupon = userCouponRepository.findByIdForUser(couponId, usage.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND, "id=" + couponId));
        CouponStateMachine.transit(coupon.getStatus(), CouponTransition.EXPIRE_ON_LOCK);  // 快检：LOCKED→EXPIRED
        if (!couponUsageRepository.casExpireByUsageNo(usageNo, usage.getUserId())) {
            throw new TradeCasRejectedException("usage-expire", usageNo);
        }
        if (!userCouponRepository.casExpireOnLock(couponId, usage.getUserId())) {
            throw new TradeCasRejectedException("coupon-expire-on-lock", couponId);
        }
        couponEventRepository.append(CouponEvent.expired(coupon, usage, now));
    }

    /**
     * 锁定超时收场（L5，2026-09-15，扫描器路径）。三件事一个事务（F1 的教训——收场链
     * 拆在事务外会在中途崩溃时留下半截）：占用单 占用中→超时关闭(4)、
     * 券 已锁定→(未过期?待使用:已过期)（IF 分流同 casRelease）、落 LOCK_TIMEOUT 事件（含订单号）。
     *
     * @return true=本次收场；false=无需收场（占用单已非占用中=订单其实说过话；或券已非锁定态）——幂等 no-op
     */
    @Transactional
    public boolean timeoutClose(String usageNo, LocalDateTime now) {
        return doTimeoutClose(usageNo, null, now);
    }

    /** 扫描器已持有 task.userId：按 usageNo+userId 单分片读，不再广播 64 张表。 */
    @Transactional
    public boolean timeoutClose(String usageNo, long userId, LocalDateTime now) {
        return doTimeoutClose(usageNo, userId, now);
    }

    private boolean doTimeoutClose(String usageNo, Long userId, LocalDateTime now) {
        CouponUsage usage = (userId == null
                ? couponUsageRepository.findByUsageNo(usageNo)
                : couponUsageRepository.findByUsageNo(usageNo, userId)).orElse(null);
        if (usage == null || usage.getStatus() != UsageStatus.OCCUPYING) {
            return false;   // 已核销/已释放/已过期关闭/已超时关闭——窗口内订单说过话，或已收场过
        }
        UserCoupon coupon = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId()).orElse(null);
        if (coupon == null || coupon.getStatus() != CouponStatus.LOCKED) {
            return false;   // 券已不在锁定态（异常残留）——不动，交由审计/人工
        }
        CouponStateMachine.transit(coupon.getStatus(), CouponTransition.LOCK_TIMEOUT);   // 快检：LOCKED→USABLE
        // 加锁顺序纪律（2026-10-15 修复死锁对）：先占用单、后券——与核销/释放/过期收场三条链路统一。
        // 旧版先券后占用单，与那三条正好相反：对方握着占用单行锁等券、本方法握着券行锁等占用单，
        // 同时进行时形成 AB-BA 死锁（数据库会终止其中一方；核销侧被终止时调用方收 500）。
        // 第一刀占用单当裁决：砍空 = 有并发者抢先收场（核销/释放/过期收场恰在同瞬间完成）——
        // 什么都没改，安全返回 false（订单其实说过话，无需收场）。
        if (!couponUsageRepository.casTimeoutByUsageNo(usageNo, usage.getUserId())) {
            return false;
        }
        // 第二刀券砍空 = 异常残留（持有占用单锁之后，其余链路都被挡在占用单外，理论上无人能再动券）——
        // 必须抛出让事务整体回滚：绝不能 return false，那会把"占用单已超时关闭、券还锁定"的
        // 半截状态提交掉（旧版此处忽略返回值还继续写事件，同款隐患一并消除）。
        if (!userCouponRepository.casTimeoutClose(coupon.getId(), usage.getUserId())) {
            throw new TradeCasRejectedException("coupon-timeout", coupon.getId());
        }
        couponEventRepository.append(CouponEvent.lockTimeout(coupon, usage, now));
        return true;
    }

    @Transactional
    public ReleaseResult release(ReleaseRequest req, LocalDateTime now) {
        return doRelease(req, null, now);
    }

    /** 编排层已持有 usage.userId：单分片读；参数语义同兼容入口。 */
    @Transactional
    public ReleaseResult release(ReleaseRequest req, long userId, LocalDateTime now) {
        return doRelease(req, userId, now);
    }

    private ReleaseResult doRelease(ReleaseRequest req, Long userId, LocalDateTime now) {
        CouponUsage usage = readUsage(req.usageNo(), userId)
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
        usage.checkReleasable();   // CONFIRMED → 拒绝（资损红线：一张券花两次）
        if (usage.getStatus() != UsageStatus.OCCUPYING) {
            // L15（2026-09-15 随 L5 清偿）：合同已终结（已释放/过期关闭/超时关闭）=取消意图已达成，
            // 当幂等成功返回券**真实状态**——旧实现硬编码"待使用"，释放时已过期/已被收场的券
            // 重放会显示错状态；迟到取消报错反而制造工单
            UserCoupon fresh = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId()).orElse(null);
            return new ReleaseResult(req.usageNo(),
                    fresh != null ? fresh.getStatus().getDesc() : CouponStatus.USABLE.getDesc(), true);
        }
        if (!couponUsageRepository.casRelease(req.usageNo(), usage.getUserId(), now)) {
            throw new TradeCasRejectedException("usage-release", req.usageNo());
        }
        UserCoupon coupon = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));
        if (!userCouponRepository.casRelease(usage.getUserCouponId(), usage.getUserId())) {
            throw new TradeCasRejectedException("coupon-release", usage.getUserCouponId());
        }
        couponEventRepository.append(CouponEvent.released(coupon, usage, now));
        // 释放后券的实际状态：未过期=待使用；已过期=已过期（不退回可用态）
        UserCoupon fresh = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId()).orElseThrow();
        return new ReleaseResult(req.usageNo(), fresh.getStatus().getDesc(), false);
    }
    /**
     * 退款（2026-10-18 行业标准版）：
     * <ul>
     *   <li>按行 + 数量部分退款；不传 items = 退剩余全部；</li>
     *   <li>refundNo 幂等：同号同参重放返回首次结果，同号异参冲突；</li>
     *   <li>退款金额读核销时落下的行级分摊快照，不按当前活动/模板重算；</li>
     *   <li>部分退款券保持已核销；整单退完且券在有效期内 → 退回待使用（一券限退 N 次）。</li>
     * </ul>
     */
    /**
     * 兼容入口：旧调用方/测试只传两个整数上限；整单退券按默认 true。
     * <b>生产不要用</b>——它会绕过租户的“整单退是否退券”政策。
     */
    @Deprecated
    @Transactional
    public RefundResult refundConfirm(RefundRequest req, LocalDateTime now,
                                      int maxRefundTimes, int maxReturnTimes) {
        return doRefundConfirm(req, null, now,
                new RefundPolicy(maxRefundTimes, maxReturnTimes, true));
    }

    /**
     * 退款事务（环 3）：政策由调用方按券的 appId 解析后传入；
     * 部分退不退券、整单退是否退券、最多退款/退券次数都在策略里。
     */
    @Transactional
    public RefundResult refundConfirm(RefundRequest req, LocalDateTime now, RefundPolicy policy) {
        return doRefundConfirm(req, null, now, policy);
    }

    /** 编排层已持有 usage.userId：首读单分片；锁行仍用快照里的 userId。 */
    @Transactional
    public RefundResult refundConfirm(RefundRequest req, long userId, LocalDateTime now,
                                      RefundPolicy policy) {
        return doRefundConfirm(req, userId, now, policy);
    }

    private RefundResult doRefundConfirm(RefundRequest req, Long userId, LocalDateTime now,
                                         RefundPolicy policy) {
        if (req.refundNo() == null || req.refundNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "退款单号不能为空");
        }
        if (req.refundNo().length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "退款单号最长 64 字符（= 存储列宽）");
        }
        // 先校验行参数，再算请求摘要——摘要会对行号排序，空行号会让排序抛空指针
        if (req.items() != null) {
            for (RefundItem item : req.items()) {
                if (item == null || item.lineNo() == null || item.lineNo().isBlank()
                        || item.quantity() == null || item.quantity() <= 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "退款行号不能为空、退款数量必须为正");
                }
            }
        }
        String digest = requestDigest(req);

        CouponRefundOrderPO existing = couponRefundOrderMapper.selectByRefundNo(req.refundNo());
        if (existing != null) {
            return replayRefund(existing, req, digest);
        }

        CouponUsage snapshot = readUsage(req.usageNo(), userId)
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
        // 行锁：同一占用单的退款串行化——累计退款数量/金额的校验必须在锁内
        CouponUsage usage = couponUsageRepository.findByUsageNoForUpdate(
                        req.usageNo(), snapshot.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));

        // 不在插入前做 SELECT ... FOR UPDATE：退款单不存在时，RR 会给唯一索引加间隙锁，
        // 递增退款单号会让不同占用单/不同用户互相等待，甚至死锁。
        // 同号由 uk_refund_no 唯一键 + 插入 catch 保证：撞键后重读并重放。
        if (usage.getStatus() != UsageStatus.CONFIRMED
                && usage.getStatus() != UsageStatus.PARTIALLY_REFUNDED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "占用单状态=" + usage.getStatus().getDesc() + "，仅已核销/部分退款可退款");
        }
        int refundCount = couponRefundOrderMapper.countByUsageNo(req.usageNo());
        if (refundCount >= policy.maxRefundTimes()) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "该占用单退款次数已达上限 " + policy.maxRefundTimes() + " 次");
        }

        UserCoupon coupon = userCouponRepository.findByIdForUser(usage.getUserCouponId(), usage.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));

        // 资金边界守卫（V6 2B）：真实退款在事务入口（券已定位、尚未写任何行）拒绝沙箱租户。
        // 幂等重放路径在上面已提前返回——已入账的退款重放不产生新的资金变动，不受此守卫影响。
        environmentGuard.assertProd(coupon.getAppId(), "refund");

        List<CouponShareDetailPO> shares = couponShareDetailMapper.selectPositiveByUsageNo(req.usageNo());
        if (shares.isEmpty()) {
            // 有分账行但全是 0/负 = 数据损坏；不能静默降级成“老式退款”（无明细、账单不可见）
            long shareSum = couponShareDetailMapper.sumShareByUsageNo(req.usageNo());
            if (shareSum != 0) {
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "分账数据异常：存在分账行但无正行，拒绝降级为老式退款");
            }
            return refundWithoutLineData(req, usage, coupon, digest, now,
                    policy.maxReturnTimes(), policy.fullRefundReturnCoupon());
        }
        return refundWithLineData(req, usage, coupon, digest, now,
                policy.maxReturnTimes(), policy.fullRefundReturnCoupon(), shares);
    }

    /** 行级退款：按核销快照逐行算“本次退多少数量、冲回多少优惠与各方出资” */
    private RefundResult refundWithLineData(RefundRequest req, CouponUsage usage, UserCoupon coupon,
                                            String digest, LocalDateTime now, int maxReturnTimes,
                                            boolean fullRefundReturnCoupon,
                                            List<CouponShareDetailPO> shares) {
        List<CouponRefundDetailPO> previous = couponRefundDetailMapper.selectByUsageNo(req.usageNo());
        Map<String, Integer> refundedQty = new HashMap<>();
        Map<String, long[]> refundedAmt = new HashMap<>();
        for (CouponRefundDetailPO detail : previous) {
            refundedQty.merge(detail.getOrderLineNo(), detail.getRefundQty(), Integer::sum);
            long[] amount = refundedAmt.computeIfAbsent(detail.getOrderLineNo(), k -> new long[2]);
            amount[0] += detail.getRefundDiscountAmt();
            amount[1] += detail.getRefundPlatformAmt();
        }

        boolean explicit = req.items() != null && !req.items().isEmpty();
        Map<String, Integer> requested = new LinkedHashMap<>();
        if (explicit) {
            for (RefundItem item : req.items()) {
                if (item == null) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "退款行不能为空");
                }
                if (item.lineNo() == null || item.lineNo().isBlank()) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "退款行号不能为空");
                }
                if (item.quantity() == null || item.quantity() <= 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "退款数量必须为正: " + item.lineNo());
                }
                if (requested.putIfAbsent(item.lineNo(), item.quantity()) != null) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "退款行号重复: " + item.lineNo());
                }
            }
        }

        List<RefundLinePlan> plans = new ArrayList<>();
        for (CouponShareDetailPO share : shares) {
            String lineNo = share.getOrderLineNo() == null ? "MAIN" : share.getOrderLineNo();
            int originalQty = share.getOrderLineQuantity() == null ? 1 : share.getOrderLineQuantity();
            int qty;
            if (explicit) {
                Integer wanted = requested.remove(lineNo);
                if (wanted == null) {
                    continue;   // 本次没退这一行
                }
                qty = wanted;
            } else {
                qty = originalQty - refundedQty.getOrDefault(lineNo, 0);   // 退剩余全部
            }
            if (qty <= 0) {
                if (!explicit) {
                    continue;   // 整单退的语义：这一行之前已经退满，跳过
                }
                throw new BizException(ErrorCode.PARAM_ERROR, "退款数量必须为正: " + lineNo);
            }
            int already = refundedQty.getOrDefault(lineNo, 0);
            // 用 long 比较：already + qty 都是 int，恶意传 quantity=Integer.MAX_VALUE
            // 时相加会溢出成负数、绕过“累计不超原数量”的校验（环 3 对抗审查发现）
            if ((long) already + qty > originalQty) {
                throw new BizException(ErrorCode.PARAM_ERROR, String.format(
                        "商品行 %s 累计退款数量超过原数量（原=%d，已退=%d，本次=%d）",
                        lineNo, originalQty, already, qty));
            }

            long lineDiscount = share.getShareTotal();
            long prevDiscount = refundedAmt.getOrDefault(lineNo, new long[2])[0];
            long prevPlatform = refundedAmt.getOrDefault(lineNo, new long[2])[1];
            // 单位优惠按“前 n-1 个单位取整、最后一个单位拿余数”排布；
            // 累计到 U 个单位 = U×base + (U==原数量 ? 余数 : 0)，多次退款按累计差算，不重不漏。
            long cumDiscount = cumulativeRefund(lineDiscount, originalQty, already + qty);
            long thisDiscount = cumDiscount - prevDiscount;
            long cumPlatform = lineDiscount == 0 ? 0
                    : share.getPlatformBurden() * cumDiscount / lineDiscount;
            long thisPlatform = cumPlatform - prevPlatform;
            if (thisPlatform < 0) {
                thisPlatform = 0;
            }
            if (thisPlatform > thisDiscount) {
                thisPlatform = thisDiscount;
            }
            plans.add(new RefundLinePlan(lineNo, qty, thisDiscount,
                    thisPlatform, thisDiscount - thisPlatform,
                    share.getAppId(), share.getSettlePartyId(),
                    share.getQualityFlags() == null ? 0 : share.getQualityFlags(),
                    share.getSettleEligible() == null || share.getSettleEligible() == 1));
        }

        if (!requested.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "退款行不存在: " + requested.keySet());
        }
        if (plans.isEmpty()) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT, "没有可退数量（该占用单已全部退款）");
        }

        // 全退判定：所有正分账行在本次之后都退满，才算整单退（决定券是否退回可用）
        Map<String, Integer> thisQty = new HashMap<>();
        for (RefundLinePlan plan : plans) {
            thisQty.merge(plan.lineNo(), plan.qty(), Integer::sum);
        }
        boolean full = true;
        for (CouponShareDetailPO share : shares) {
            String lineNo = share.getOrderLineNo() == null ? "MAIN" : share.getOrderLineNo();
            int originalQty = share.getOrderLineQuantity() == null ? 1 : share.getOrderLineQuantity();
            int totalQty = refundedQty.getOrDefault(lineNo, 0) + thisQty.getOrDefault(lineNo, 0);
            if (totalQty < originalQty) {
                full = false;
                break;
            }
        }

        long totalDiscount = plans.stream().mapToLong(RefundLinePlan::discount).sum();
        long totalPlatform = plans.stream().mapToLong(RefundLinePlan::platform).sum();
        long totalMerchant = plans.stream().mapToLong(RefundLinePlan::merchant).sum();

        UsageStatus target = full ? UsageStatus.REFUNDED : UsageStatus.PARTIALLY_REFUNDED;
        CouponRefundOrderPO order = newRefundOrder(req, usage, coupon, digest, target,
                full ? "FULL" : "PARTIAL", totalDiscount, totalPlatform, totalMerchant,
                plans.size(), true);
        try {
            couponRefundOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            CouponRefundOrderPO raced = couponRefundOrderMapper.selectByRefundNoForUpdate(req.refundNo());
            if (raced != null) {
                return replayRefund(raced, req, digest);
            }
            throw e;
        }

        boolean returned = false;
        if (full) {
            returned = returnCouponIfAllowed(coupon, usage, req.refundNo(), maxReturnTimes,
                    fullRefundReturnCoupon);
        }
        UserCoupon freshCoupon = userCouponRepository.findByIdForUser(coupon.getId(), coupon.getUserId()).orElse(coupon);
        order.setCouponReturned(returned ? 1 : 0);
        order.setCouponStatus(freshCoupon.getStatus().name());
        couponRefundOrderMapper.updateById(order);

        for (RefundLinePlan plan : plans) {
            CouponRefundDetailPO detail = new CouponRefundDetailPO();
            detail.setRefundNo(req.refundNo());
            detail.setUsageNo(req.usageNo());
            detail.setOrderLineNo(plan.lineNo());
            detail.setUserCouponId(coupon.getId());
            detail.setUserId(coupon.getUserId());
            detail.setTemplateId(coupon.getTemplateId());
            detail.setAppId(plan.appId());
            detail.setSettlePartyId(plan.settlePartyId());
            detail.setRefundQty(plan.qty());
            detail.setRefundDiscountAmt(plan.discount());
            detail.setRefundPlatformAmt(plan.platform());
            detail.setRefundMerchantAmt(plan.merchant());
            // 质量标从原分账行镜像（V47）：被排除的核销其冲回同样排除——见 RefundLinePlan 注释
            detail.setQualityFlags(plan.qualityFlags());
            detail.setSettleEligible(plan.settleEligible() ? 1 : 0);
            couponRefundDetailMapper.insert(detail);
        }

        if (!couponUsageRepository.casRefundState(req.usageNo(), usage.getUserId(), target)) {
            throw new TradeCasRejectedException("usage-refund", req.usageNo());
        }
        couponEventRepository.append(CouponEvent.refund(coupon, usage, req.refundNo(), totalDiscount, now));

        return new RefundResult(req.usageNo(), req.refundNo(), full ? "FULL" : "PARTIAL",
                freshCoupon.getStatus().getDesc(), target.getDesc(), totalDiscount, false);
    }

    /**
     * 老式核销（没有行级分账）的退款：只能整单退，且没有出资方明细可冲。
     * 保留该路径是为了过渡期兼容；新核销一律走行级，退款就能按行 + 数量。
     */
    private RefundResult refundWithoutLineData(RefundRequest req, CouponUsage usage, UserCoupon coupon,
                                               String digest, LocalDateTime now, int maxReturnTimes,
                                               boolean fullRefundReturnCoupon) {
        if (req.items() != null && !req.items().isEmpty()) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "该占用单是旧式核销（没有行级分账），只能整单退款");
        }
        long amount = usage.getDiscountAmt() == null ? 0 : usage.getDiscountAmt();
        CouponRefundOrderPO order = newRefundOrder(req, usage, coupon, digest,
                UsageStatus.REFUNDED, "FULL", amount, 0, 0, 0, false);
        try {
            couponRefundOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            CouponRefundOrderPO raced = couponRefundOrderMapper.selectByRefundNoForUpdate(req.refundNo());
            if (raced != null) {
                return replayRefund(raced, req, digest);
            }
            throw e;
        }

        boolean returned = returnCouponIfAllowed(coupon, usage, req.refundNo(), maxReturnTimes,
                fullRefundReturnCoupon);
        UserCoupon freshCoupon = userCouponRepository.findByIdForUser(coupon.getId(), coupon.getUserId()).orElse(coupon);
        order.setCouponReturned(returned ? 1 : 0);
        order.setCouponStatus(freshCoupon.getStatus().name());
        couponRefundOrderMapper.updateById(order);

        if (!couponUsageRepository.casRefundState(req.usageNo(), usage.getUserId(), UsageStatus.REFUNDED)) {
            throw new TradeCasRejectedException("usage-refund", req.usageNo());
        }
        couponEventRepository.append(CouponEvent.refund(coupon, usage, req.refundNo(), amount, now));

        return new RefundResult(req.usageNo(), req.refundNo(), "FULL",
                freshCoupon.getStatus().getDesc(), UsageStatus.REFUNDED.getDesc(), amount, false);
    }

    /** 整单退：券在有效期内回待使用、已过期转已过期；一券最多退 N 次（防“下单-退款”刷券） */
    private boolean returnCouponIfAllowed(UserCoupon coupon, CouponUsage usage,
                                          String refundNo, int maxReturnTimes,
                                          boolean fullRefundReturnCoupon) {
        if (!fullRefundReturnCoupon) {
            return false;   // 租户显式关闭：整单退也不退券，券保持已核销
        }
        int returnedTimes = couponReturnLogMapper.countByCoupon(coupon.getId());
        if (returnedTimes >= maxReturnTimes) {
            return false;
        }
        // 状态机快检：USED → USABLE 是唯一允许的出边（券已过期时 SQL 的 IF 会把它分到 EXPIRED）
        if (coupon.getStatus() != CouponStatus.USED) {
            return false;
        }
        CouponStateMachine.transit(coupon.getStatus(), CouponTransition.REFUND_RETURN);
        if (!userCouponRepository.casReturnOnRefund(coupon.getId(), usage.getUserId())) {
            return false;
        }
        CouponReturnLogPO log = new CouponReturnLogPO();
        log.setUserCouponId(coupon.getId());
        log.setRefundNo(refundNo);
        couponReturnLogMapper.insert(log);
        return true;
    }

    private CouponRefundOrderPO newRefundOrder(RefundRequest req, CouponUsage usage, UserCoupon coupon,
                                               String digest, UsageStatus target, String type,
                                               long discount, long platform, long merchant,
                                               int itemCount, boolean fundingKnown) {
        CouponRefundOrderPO order = new CouponRefundOrderPO();
        order.setRefundNo(req.refundNo());
        order.setUsageNo(req.usageNo());
        order.setUserCouponId(coupon.getId());
        order.setUserId(coupon.getUserId());
        order.setTemplateId(coupon.getTemplateId());
        order.setAppId(coupon.getAppId());
        order.setOrderNo(usage.getOrderNo());
        order.setRefundType(type);
        order.setRefundDiscountAmt(discount);
        order.setRefundPlatformAmt(platform);
        order.setRefundMerchantAmt(merchant);
        order.setRequestDigest(digest);
        order.setItemCount(itemCount);
        order.setFundingKnown(fundingKnown ? 1 : 0);
        order.setCouponReturned(0);
        order.setCouponStatus(coupon.getStatus().name());
        order.setUsageStatus(target.name());
        return order;
    }

    /** 同号重放：同参返回首次结果；异参（或单号已被别的占用单使用）报冲突 */
    private RefundResult replayRefund(CouponRefundOrderPO order, RefundRequest req, String digest) {
        if (!order.getUsageNo().equals(req.usageNo())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "退款单号已用于其他占用单: " + req.refundNo());
        }
        if (!order.getRequestDigest().equals(digest)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "同一退款单号请求参数不一致");
        }
        String couponStatusName = order.getCouponStatus();
        if (couponStatusName == null) {
            // 历史退款单可能没有 user_id（V24 之前）；有则单表命中，没有才退回冷路径广播。
            couponStatusName = (order.getUserId() == null
                    ? userCouponRepository.findById(order.getUserCouponId())
                    : userCouponRepository.findByIdForUser(order.getUserCouponId(), order.getUserId()))
                    .map(c -> c.getStatus().name()).orElse(null);
        }
        String usageStatusName = order.getUsageStatus();
        if (usageStatusName == null) {
            usageStatusName = (order.getUserId() == null
                    ? couponUsageRepository.findByUsageNo(order.getUsageNo())
                    : couponUsageRepository.findByUsageNo(order.getUsageNo(), order.getUserId()))
                    .map(u -> u.getStatus().name()).orElse(null);
        }
        String couponDesc = couponStatusName == null ? null
                : CouponStatus.valueOf(couponStatusName).getDesc();
        String usageDesc = usageStatusName == null ? null
                : UsageStatus.valueOf(usageStatusName).getDesc();
        return new RefundResult(order.getUsageNo(), order.getRefundNo(), order.getRefundType(),
                couponDesc, usageDesc, order.getRefundDiscountAmt(), true);
    }

    /** 累计到 units 个单位的优惠：单位优惠前 n-1 个取整，最后一个单位拿余数 */
    private long cumulativeRefund(long lineDiscount, int originalQty, int units) {
        if (units <= 0 || lineDiscount <= 0) {
            return 0;
        }
        int qty = Math.max(originalQty, 1);
        long base = lineDiscount / qty;
        long remainder = lineDiscount % qty;
        long total = base * units;
        if (units >= qty) {
            total += remainder;
        }
        return total;
    }

    /** 请求摘要：usageNo + 排序后的“行号:数量”（不传 items = FULL） */
    private String requestDigest(RefundRequest req) {
        StringBuilder raw = new StringBuilder(req.usageNo());
        if (req.items() == null || req.items().isEmpty()) {
            raw.append("|FULL");
        } else {
            req.items().stream()
                    .sorted(Comparator.comparing(RefundItem::lineNo))
                    .forEach(item -> raw.append('|').append(item.lineNo())
                            .append(':').append(item.quantity()));
        }
        return sha256(raw.toString());
    }

    private String sha256(String value) {
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

    /**
     * 一行退款计划：本次退多少数量、冲回多少优惠与平台/商家出资。
     * qualityFlags/settleEligible 从原分账行镜像（V47 审查 A 簇）：被排除的核销从未进
     * 真实账单，它的冲回若以 eligible=1 进账单=从未计入的钱被冲回、净额无声压低——
     * 退款明细必须与原分账行同生共死（null 容错是给 V47 前的存量行：列有默认值 0/1）。
     */
    private record RefundLinePlan(String lineNo, int qty, long discount,
                                  long platform, long merchant,
                                  String appId, String settlePartyId,
                                  int qualityFlags, boolean settleEligible) {
    }

}
