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
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.domain.statemachine.CouponStateMachine;
import com.newcoupon.domain.statemachine.CouponTransition;
import com.newcoupon.infrastructure.persistence.CouponReleaseTaskGateway;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundDetailMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundOrderMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponReturnLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
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

    private final UserCouponRepository userCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CouponEventRepository couponEventRepository;
    private final CouponShareDetailMapper couponShareDetailMapper;
    private final CouponRefundOrderMapper couponRefundOrderMapper;
    private final CouponRefundDetailMapper couponRefundDetailMapper;
    private final CouponReturnLogMapper couponReturnLogMapper;
    private final CouponReleaseTaskGateway releaseTaskGateway;
    private final long lockTimeoutMinutes;

    public TradeTxService(UserCouponRepository userCouponRepository,
                          CouponUsageRepository couponUsageRepository,
                          CouponEventRepository couponEventRepository,
                          CouponShareDetailMapper couponShareDetailMapper,
                          CouponRefundOrderMapper couponRefundOrderMapper,
                          CouponRefundDetailMapper couponRefundDetailMapper,
                          CouponReturnLogMapper couponReturnLogMapper,
                          CouponReleaseTaskGateway releaseTaskGateway,
                          @Value("${coupon.trade.lock-timeout-minutes:30}") long lockTimeoutMinutes) {
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.couponEventRepository = couponEventRepository;
        this.couponShareDetailMapper = couponShareDetailMapper;
        this.couponRefundOrderMapper = couponRefundOrderMapper;
        this.couponRefundDetailMapper = couponRefundDetailMapper;
        this.couponReturnLogMapper = couponReturnLogMapper;
        this.releaseTaskGateway = releaseTaskGateway;
        this.lockTimeoutMinutes = lockTimeoutMinutes;
    }

    @Transactional
    public LockResult lock(LockRequest req, LocalDateTime now) {
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
        couponEventRepository.append(CouponEvent.locked(coupon, usage, now));
        // L5：注册锁定超时任务（同一事务——"锁了必有兜底"；deadline=占用时间+窗口，
        // 测试用 coupon.trade.lock-timeout-minutes=0 使任务即刻到期）
        releaseTaskGateway.register(usageNo, coupon.getId(), req.userId(),
                now.plusMinutes(lockTimeoutMinutes));
        return new LockResult(usageNo, req.couponId(), CouponStatus.LOCKED.getDesc(), false);
    }

    @Transactional
    public ConfirmResult confirm(ConfirmRequest req, LocalDateTime now, FundingSplit split) {
        return confirm(req, now, split, java.util.List.of());
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
        CouponUsage usage = couponUsageRepository.findByUsageNo(req.usageNo())
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
        if (usage.getStatus() == UsageStatus.CONFIRMED) {
            // 幂等：支付回调重复推送，第一次已核销——直接当成功返回
            return new ConfirmResult(req.usageNo(), CouponStatus.USED.getDesc(),
                    usage.getDiscountAmt(), true);
        }
        usage.checkConfirmable();   // RELEASED → 拒绝
        if (!couponUsageRepository.casConfirm(req.usageNo(), req.discountAmt(), now)) {
            throw new TradeCasRejectedException("usage-confirm", req.usageNo());
        }
        UserCoupon coupon = userCouponRepository.findById(usage.getUserCouponId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));
        if (!userCouponRepository.casConfirm(usage.getUserCouponId(), usage.getUserId())) {
            throw new TradeCasRejectedException("coupon-confirm", usage.getUserCouponId());
        }
        couponEventRepository.append(CouponEvent.used(coupon, usage, req.discountAmt(), now));

        // ════ 第 2 环：出资拆分落表（与核销同一事务——原子）════
        if (split != null) {
            if (lineShares != null && !lineShares.isEmpty()) {
                insertLineShares(req, coupon, usage, split, lineShares);
            } else {
                insertOrderLevelShare(req, coupon, usage, split);
            }
        }

        return new ConfirmResult(req.usageNo(), CouponStatus.USED.getDesc(), req.discountAmt(), false);
    }

    /** 订单粒度分账（有 split 但没有行级分摊时的兼容路径：没有行号/行金额/行数量） */
    private void insertOrderLevelShare(ConfirmRequest req, UserCoupon coupon, CouponUsage usage,
                                       FundingSplit split) {
        CouponShareDetailPO share = new CouponShareDetailPO();
        share.setUserCouponId(coupon.getId());
        share.setUserId(coupon.getUserId());
        share.setTemplateId(coupon.getTemplateId());
        share.setUsageNo(req.usageNo());
        share.setOrderNo(usage.getOrderNo());
        share.setPlatformBurden(split.platformBurden());
        share.setMerchantBurden(split.merchantBurden());
        share.setShareTotal(split.total());
        couponShareDetailMapper.insert(share);
    }

    /**
     * 行级分账：一行商品一条记录。落库前做三条守恒校验——任何一条不成立都抛异常回滚，
     * 绝不让“行分摊合计 ≠ 券优惠”的账落进库（这是后面按行退款的唯一依据）。
     */
    private void insertLineShares(ConfirmRequest req, UserCoupon coupon, CouponUsage usage,
                                  FundingSplit split,
                                  java.util.List<com.newcoupon.application.dto.TradeDtos.LineShare> lineShares) {
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
            share.setUsageNo(req.usageNo());
            share.setOrderNo(usage.getOrderNo());
            share.setOrderLineNo(line.lineNo());
            share.setOrderLineAmount(line.lineAmount());
            share.setOrderLineQuantity(line.quantity());
            share.setPlatformBurden(line.platformBurden());
            share.setMerchantBurden(line.merchantBurden());
            share.setShareTotal(line.discountAmt());
            couponShareDetailMapper.insert(share);
        }
    }

    /**
     * 锁定中过期的收场（L2，2026-09-12）：核销因"锁定期间券到期"被拒后，由编排层调用。
     * 三件事一个事务：占用单 占用中→过期关闭（第三种终结，非核销非释放）、
     * 券 已锁定→已过期（终态）、落 EXPIRED 事件（含订单号，供对账归因）。
     * 补贴不承担——过期券不付钱。
     */
    @Transactional
    public void expireOnLock(String usageNo, long couponId, LocalDateTime now) {
        CouponUsage usage = couponUsageRepository.findByUsageNo(usageNo)
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, usageNo));
        if (usage.getStatus() != UsageStatus.OCCUPYING) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "占用单状态=" + usage.getStatus().getDesc() + "，仅占用中可过期关闭");
        }
        UserCoupon coupon = userCouponRepository.findById(couponId)
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND, "id=" + couponId));
        CouponStateMachine.transit(coupon.getStatus(), CouponTransition.EXPIRE_ON_LOCK);  // 快检：LOCKED→EXPIRED
        if (!couponUsageRepository.casExpireByUsageNo(usageNo)) {
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
        CouponUsage usage = couponUsageRepository.findByUsageNo(usageNo).orElse(null);
        if (usage == null || usage.getStatus() != UsageStatus.OCCUPYING) {
            return false;   // 已核销/已释放/已过期关闭/已超时关闭——窗口内订单说过话，或已收场过
        }
        UserCoupon coupon = userCouponRepository.findById(usage.getUserCouponId()).orElse(null);
        if (coupon == null || coupon.getStatus() != CouponStatus.LOCKED) {
            return false;   // 券已不在锁定态（异常残留）——不动，交由审计/人工
        }
        CouponStateMachine.transit(coupon.getStatus(), CouponTransition.LOCK_TIMEOUT);   // 快检：LOCKED→USABLE
        // 加锁顺序纪律（2026-10-15 修复死锁对）：先占用单、后券——与核销/释放/过期收场三条链路统一。
        // 旧版先券后占用单，与那三条正好相反：对方握着占用单行锁等券、本方法握着券行锁等占用单，
        // 同时进行时形成 AB-BA 死锁（数据库会终止其中一方；核销侧被终止时调用方收 500）。
        // 第一刀占用单当裁决：砍空 = 有并发者抢先收场（核销/释放/过期收场恰在同瞬间完成）——
        // 什么都没改，安全返回 false（订单其实说过话，无需收场）。
        if (!couponUsageRepository.casTimeoutByUsageNo(usageNo)) {
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
        CouponUsage usage = couponUsageRepository.findByUsageNo(req.usageNo())
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
        usage.checkReleasable();   // CONFIRMED → 拒绝（资损红线：一张券花两次）
        if (usage.getStatus() != UsageStatus.OCCUPYING) {
            // L15（2026-09-15 随 L5 清偿）：合同已终结（已释放/过期关闭/超时关闭）=取消意图已达成，
            // 当幂等成功返回券**真实状态**——旧实现硬编码"待使用"，释放时已过期/已被收场的券
            // 重放会显示错状态；迟到取消报错反而制造工单
            UserCoupon fresh = userCouponRepository.findById(usage.getUserCouponId()).orElse(null);
            return new ReleaseResult(req.usageNo(),
                    fresh != null ? fresh.getStatus().getDesc() : CouponStatus.USABLE.getDesc(), true);
        }
        if (!couponUsageRepository.casRelease(req.usageNo(), now)) {
            throw new TradeCasRejectedException("usage-release", req.usageNo());
        }
        UserCoupon coupon = userCouponRepository.findById(usage.getUserCouponId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));
        if (!userCouponRepository.casRelease(usage.getUserCouponId(), usage.getUserId())) {
            throw new TradeCasRejectedException("coupon-release", usage.getUserCouponId());
        }
        couponEventRepository.append(CouponEvent.released(coupon, usage, now));
        // 释放后券的实际状态：未过期=待使用；已过期=已过期（不退回可用态）
        UserCoupon fresh = userCouponRepository.findById(usage.getUserCouponId()).orElseThrow();
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
    @Transactional
    public RefundResult refundConfirm(RefundRequest req, LocalDateTime now,
                                      int maxRefundTimes, int maxReturnTimes) {
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

        CouponUsage snapshot = couponUsageRepository.findByUsageNo(req.usageNo())
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
        // 行锁：同一占用单的退款串行化——累计退款数量/金额的校验必须在锁内
        CouponUsage usage = couponUsageRepository.findByUsageNoForUpdate(
                        req.usageNo(), snapshot.getUserId())
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));

        // 并发同号：拿到行锁后再查一次（后到者会看到先到者已提交的退款单）
        existing = couponRefundOrderMapper.selectByRefundNo(req.refundNo());
        if (existing != null) {
            return replayRefund(existing, req, digest);
        }

        if (usage.getStatus() != UsageStatus.CONFIRMED
                && usage.getStatus() != UsageStatus.PARTIALLY_REFUNDED) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "占用单状态=" + usage.getStatus().getDesc() + "，仅已核销/部分退款可退款");
        }
        int refundCount = couponRefundOrderMapper.countByUsageNo(req.usageNo());
        if (refundCount >= maxRefundTimes) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "该占用单退款次数已达上限 " + maxRefundTimes + " 次");
        }

        UserCoupon coupon = userCouponRepository.findById(usage.getUserCouponId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));

        List<CouponShareDetailPO> shares = couponShareDetailMapper.selectPositiveByUsageNo(req.usageNo());
        if (shares.isEmpty()) {
            return refundWithoutLineData(req, usage, coupon, digest, now, maxReturnTimes);
        }
        return refundWithLineData(req, usage, coupon, digest, now, maxReturnTimes, shares);
    }

    /** 行级退款：按核销快照逐行算“本次退多少数量、冲回多少优惠与各方出资” */
    private RefundResult refundWithLineData(RefundRequest req, CouponUsage usage, UserCoupon coupon,
                                            String digest, LocalDateTime now, int maxReturnTimes,
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
            if (already + qty > originalQty) {
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
                    thisPlatform, thisDiscount - thisPlatform));
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
                full ? "FULL" : "PARTIAL", totalDiscount, totalPlatform, totalMerchant, plans.size());
        try {
            couponRefundOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            CouponRefundOrderPO raced = couponRefundOrderMapper.selectByRefundNo(req.refundNo());
            if (raced != null) {
                return replayRefund(raced, req, digest);
            }
            throw e;
        }

        boolean returned = false;
        if (full) {
            returned = returnCouponIfAllowed(coupon, usage, req.refundNo(), maxReturnTimes);
        }
        UserCoupon freshCoupon = userCouponRepository.findById(coupon.getId()).orElse(coupon);
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
            detail.setRefundQty(plan.qty());
            detail.setRefundDiscountAmt(plan.discount());
            detail.setRefundPlatformAmt(plan.platform());
            detail.setRefundMerchantAmt(plan.merchant());
            couponRefundDetailMapper.insert(detail);
        }

        if (!couponUsageRepository.casRefundState(req.usageNo(), target)) {
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
                                               String digest, LocalDateTime now, int maxReturnTimes) {
        if (req.items() != null && !req.items().isEmpty()) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "该占用单是旧式核销（没有行级分账），只能整单退款");
        }
        long amount = usage.getDiscountAmt() == null ? 0 : usage.getDiscountAmt();
        CouponRefundOrderPO order = newRefundOrder(req, usage, coupon, digest,
                UsageStatus.REFUNDED, "FULL", amount, 0, 0, 0);
        try {
            couponRefundOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            CouponRefundOrderPO raced = couponRefundOrderMapper.selectByRefundNo(req.refundNo());
            if (raced != null) {
                return replayRefund(raced, req, digest);
            }
            throw e;
        }

        boolean returned = returnCouponIfAllowed(coupon, usage, req.refundNo(), maxReturnTimes);
        UserCoupon freshCoupon = userCouponRepository.findById(coupon.getId()).orElse(coupon);
        order.setCouponReturned(returned ? 1 : 0);
        order.setCouponStatus(freshCoupon.getStatus().name());
        couponRefundOrderMapper.updateById(order);

        if (!couponUsageRepository.casRefundState(req.usageNo(), UsageStatus.REFUNDED)) {
            throw new TradeCasRejectedException("usage-refund", req.usageNo());
        }
        couponEventRepository.append(CouponEvent.refund(coupon, usage, req.refundNo(), amount, now));

        return new RefundResult(req.usageNo(), req.refundNo(), "FULL",
                freshCoupon.getStatus().getDesc(), UsageStatus.REFUNDED.getDesc(), amount, false);
    }

    /** 整单退：券在有效期内回待使用、已过期转已过期；一券最多退 N 次（防“下单-退款”刷券） */
    private boolean returnCouponIfAllowed(UserCoupon coupon, CouponUsage usage,
                                          String refundNo, int maxReturnTimes) {
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
                                               long discount, long platform, long merchant, int itemCount) {
        CouponRefundOrderPO order = new CouponRefundOrderPO();
        order.setRefundNo(req.refundNo());
        order.setUsageNo(req.usageNo());
        order.setUserCouponId(coupon.getId());
        order.setUserId(coupon.getUserId());
        order.setTemplateId(coupon.getTemplateId());
        order.setOrderNo(usage.getOrderNo());
        order.setRefundType(type);
        order.setRefundDiscountAmt(discount);
        order.setRefundPlatformAmt(platform);
        order.setRefundMerchantAmt(merchant);
        order.setRequestDigest(digest);
        order.setItemCount(itemCount);
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
            couponStatusName = userCouponRepository.findById(order.getUserCouponId())
                    .map(c -> c.getStatus().name()).orElse(null);
        }
        String usageStatusName = order.getUsageStatus();
        if (usageStatusName == null) {
            usageStatusName = couponUsageRepository.findByUsageNo(order.getUsageNo())
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

    /** 一行退款计划：本次退多少数量、冲回多少优惠与平台/商家出资 */
    private record RefundLinePlan(String lineNo, int qty, long discount,
                                  long platform, long merchant) {
    }

}
