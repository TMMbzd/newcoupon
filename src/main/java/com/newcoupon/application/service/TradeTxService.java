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
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用券三段事务服务（结构纪律与 CouponIssueTxService 相同：独立类防自调用失效；
 * 编排层严禁 @Transactional 防 rollback-only 退化——见 study/04a 第 4 部分）。
 *
 * <p>三段的写入清单（每段各自一个事务）：
 * <pre>
 * 锁券 lock    = UPDATE 券状态(0→1) + INSERT 占用单 + INSERT 锁定事件
 * 核销 confirm = UPDATE 占用单(0→1,含金额) + UPDATE 券状态(1→2) + INSERT 核销事件
 * 释放 release = UPDATE 占用单(0→2) + UPDATE 券状态(1→0/3) + INSERT 释放事件
 * </pre>
 *
 * <p>CAS 0 行 → 抛 TradeCasRejectedException（事务回滚），由编排层新快照分类。
 */
@Service
public class TradeTxService {

    private final UserCouponRepository userCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CouponEventRepository couponEventRepository;

    public TradeTxService(UserCouponRepository userCouponRepository,
                          CouponUsageRepository couponUsageRepository,
                          CouponEventRepository couponEventRepository) {
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.couponEventRepository = couponEventRepository;
    }

    @Transactional
    public LockResult lock(LockRequest req, LocalDateTime now) {
        UserCoupon coupon = userCouponRepository.findById(req.couponId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND, "id=" + req.couponId()));
        if (coupon.getUserId() != req.userId()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "券不属于该用户（防拿他人券下单）");
        }
        CouponStateMachine.transit(coupon.getStatus(), CouponTransition.LOCK);   // 内存快检：USABLE→LOCKED
        if (!userCouponRepository.casLock(req.couponId())) {
            throw new TradeCasRejectedException("coupon-lock", req.couponId());
        }
        String usageNo = req.orderNo() + ":" + req.couponId();
        CouponUsage usage = couponUsageRepository.save(
                CouponUsage.occupy(coupon, req.orderNo(), usageNo, now));        // 撞uk_usage_no→DuplicateKey
        couponEventRepository.append(CouponEvent.locked(coupon, usage, now));
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
        if (!userCouponRepository.casConfirm(usage.getUserCouponId())) {
            throw new TradeCasRejectedException("coupon-confirm", usage.getUserCouponId());
        }
        couponEventRepository.append(CouponEvent.used(coupon, usage, req.discountAmt(), now));
        return new ConfirmResult(req.usageNo(), CouponStatus.USED.getDesc(), req.discountAmt(), false);
    }

    @Transactional
    public ReleaseResult release(ReleaseRequest req, LocalDateTime now) {
        CouponUsage usage = couponUsageRepository.findByUsageNo(req.usageNo())
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, req.usageNo()));
        if (usage.getStatus() == UsageStatus.RELEASED) {
            // 幂等：取消消息重复推送（阶段0验收③的核心场景）——当成功返回
            return new ReleaseResult(req.usageNo(), CouponStatus.USABLE.getDesc(), true);
        }
        usage.checkReleasable();   // CONFIRMED → 拒绝（资损红线：一张券花两次）
        if (!couponUsageRepository.casRelease(req.usageNo(), now)) {
            throw new TradeCasRejectedException("usage-release", req.usageNo());
        }
        UserCoupon coupon = userCouponRepository.findById(usage.getUserCouponId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_COUPON_NOT_FOUND));
        if (!userCouponRepository.casRelease(usage.getUserCouponId())) {
            throw new TradeCasRejectedException("coupon-release", usage.getUserCouponId());
        }
        couponEventRepository.append(CouponEvent.released(coupon, usage, now));
        // 释放后券的实际状态：未过期=待使用；已过期=已过期（不退回可用态）
        UserCoupon fresh = userCouponRepository.findById(usage.getUserCouponId()).orElseThrow();
        return new ReleaseResult(req.usageNo(), fresh.getStatus().getDesc(), false);
    }
}
