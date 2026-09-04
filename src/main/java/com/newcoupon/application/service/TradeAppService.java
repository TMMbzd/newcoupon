package com.newcoupon.application.service;

import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmResult;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.MyCoupon;
import com.newcoupon.application.dto.TradeDtos.MyCouponList;
import com.newcoupon.application.dto.TradeDtos.ReleaseRequest;
import com.newcoupon.application.dto.TradeDtos.ReleaseResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponStatus;
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

    public TradeAppService(TradeTxService tradeTxService,
                           UserCouponRepository userCouponRepository,
                           CouponUsageRepository couponUsageRepository) {
        this.tradeTxService = tradeTxService;
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
    }

    public LockResult lock(LockRequest req) {
        try {
            return tradeTxService.lock(req, LocalDateTime.now());
        } catch (DuplicateKeyException e) {
            // 撞 uk_usage_no = 同一订单重复锁同一券（回调重放）——查回当成功
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
                // 已被锁定：若正是本订单锁的 → 幂等成功；否则提示被别的订单占用
                LockResult existing = idempotentLock(req);
                if (existing != null) {
                    return existing;
                }
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "券已被其他订单锁定（orderNo=" + req.orderNo() + " 不是持有者）");
            }
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "券当前状态=" + fresh.getStatus().getDesc() + "，仅待使用可锁定");
        }
    }

    public ConfirmResult confirm(ConfirmRequest req) {
        try {
            return tradeTxService.confirm(req, LocalDateTime.now());
        } catch (TradeCasRejectedException e) {
            throw freshStateConflict("核销", e);
        }
    }

    public ReleaseResult release(ReleaseRequest req) {
        try {
            return tradeTxService.release(req, LocalDateTime.now());
        } catch (TradeCasRejectedException e) {
            // 释放 CAS 失败（占用单或券状态竞争失败）——事务已整体回滚，按状态冲突报告
            throw freshStateConflict("释放", e);
        }
    }

    /** 券包列表（status 可选过滤） */
    public MyCouponList myCoupons(long userId, CouponStatus status) {
        return new MyCouponList(userCouponRepository.findByUser(userId, status).stream()
                .map(c -> new MyCoupon(c.getId(), c.getTemplateCode(), c.getFaceValue(),
                        c.getThreshold(), c.getDiscountCap(), c.getValidEnd(),
                        c.getStatus().getDesc()))
                .toList());
    }

    // ---------- 内部 ----------

    /** 同一订单重复锁同一券的幂等出口：按 usageNo 查占用单（事务外新快照），存在即返回"已锁定" */
    private LockResult idempotentLock(LockRequest req) {
        String usageNo = req.orderNo() + ":" + req.couponId();
        return couponUsageRepository.findByUsageNo(usageNo)
                .map(u -> new LockResult(usageNo, req.couponId(), CouponStatus.LOCKED.getDesc(), true))
                .orElse(null);
    }

    /** CAS 失败的通用分类：新快照读占用单+券，给出带真实状态的错误信息 */
    private BizException freshStateConflict(String action, TradeCasRejectedException e) {
        log.warn("{} cas rejected: {}", action, e.getMessage());
        return new BizException(ErrorCode.STATE_CONFLICT,
                action + "失败：状态已被并发变更（" + e.getMessage() + "），请刷新后重试");
    }
}
