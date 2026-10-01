package com.newcoupon.application.service;

import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmResult;
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
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用券三段事务服务（结构纪律与 CouponIssueTxService 相同：独立类防自调用失效；
 * 编排层严禁 @Transactional 防 rollback-only 退化——见 study/04a 第 4 部分）。
 *
 * <p>三段的写入清单（每段各自一个事务）：
 * <pre>
 * 锁券 lock    = UPDATE 券状态(0→1) + INSERT 占用单 + INSERT 锁定事件 + INSERT 超时任务(L5)
 * 核销 confirm = UPDATE 占用单(0→1,含金额) + UPDATE 券状态(1→2) + INSERT 核销事件
 * 释放 release = UPDATE 占用单(0→2) + UPDATE 券状态(1→0/3) + INSERT 释放事件
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
    private final CouponReleaseTaskGateway releaseTaskGateway;
    private final long lockTimeoutMinutes;

    public TradeTxService(UserCouponRepository userCouponRepository,
                          CouponUsageRepository couponUsageRepository,
                          CouponEventRepository couponEventRepository,
                          CouponReleaseTaskGateway releaseTaskGateway,
                          @Value("${coupon.trade.lock-timeout-minutes:30}") long lockTimeoutMinutes) {
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.couponEventRepository = couponEventRepository;
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
    public ConfirmResult confirm(ConfirmRequest req, LocalDateTime now) {
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
        return new ConfirmResult(req.usageNo(), CouponStatus.USED.getDesc(), req.discountAmt(), false);
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
        if (!userCouponRepository.casTimeoutClose(coupon.getId(), usage.getUserId())) {
            return false;   // CAS 输家：核销/释放/过期收场恰好在同瞬间完成
        }
        couponUsageRepository.casTimeoutByUsageNo(usageNo);
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
}
