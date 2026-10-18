package com.newcoupon.application.service;

import com.newcoupon.application.dto.TradeDtos.FundingSplit;
import com.newcoupon.application.dto.TradeDtos.LineShare;
import com.newcoupon.application.service.PricingSnapshotService.SnapshotConfirm;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.infrastructure.persistence.mapper.CouponConfirmExceptionMapper;
import com.newcoupon.infrastructure.persistence.po.CouponConfirmExceptionPO;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 支付后核销异常的管理面编排（环 2）。
 *
 * <p>异常单只能从这里出：{@code CONFIRM}=按锁券时绑定的快照原价核销；
 * {@code RELEASE}=确认订单已退款/不会使用该优惠后释放。普通 release/refund 对
 * {@code CONFIRM_EXCEPTION} 一律 4004，绝不让超时任务或取消消息把券放回 USABLE。
 */
@Service
public class ConfirmExceptionService {

    private final CouponConfirmExceptionMapper exceptionMapper;
    private final com.newcoupon.domain.repository.CouponUsageRepository couponUsageRepository;
    private final PricingSnapshotService pricingSnapshotService;
    private final TradeTxService tradeTxService;

    public ConfirmExceptionService(CouponConfirmExceptionMapper exceptionMapper,
                                   com.newcoupon.domain.repository.CouponUsageRepository couponUsageRepository,
                                   PricingSnapshotService pricingSnapshotService,
                                   TradeTxService tradeTxService) {
        this.exceptionMapper = exceptionMapper;
        this.couponUsageRepository = couponUsageRepository;
        this.pricingSnapshotService = pricingSnapshotService;
        this.tradeTxService = tradeTxService;
    }

    public List<PendingException> listPending(int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        return exceptionMapper.selectPending(capped).stream()
                .map(po -> new PendingException(po.getUsageNo(), po.getAppId(), po.getUserId(),
                        po.getUserCouponId(), po.getOrderNo(), po.getPricingId(), po.getReason(),
                        po.getCreateTime()))
                .toList();
    }

    public ResolveResult resolve(String usageNo, String action, String reason) {
        String normalized = action == null ? "" : action.trim().toUpperCase();
        if (!"CONFIRM".equals(normalized) && !"RELEASE".equals(normalized)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "action 只能是 CONFIRM 或 RELEASE");
        }
        CouponUsage usage = couponUsageRepository.findByUsageNo(usageNo)
                .orElseThrow(() -> new BizException(ErrorCode.USAGE_NOT_FOUND, usageNo));
        if (usage.getStatus() != UsageStatus.CONFIRM_EXCEPTION) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "占用单状态=" + usage.getStatus().getDesc() + "，不是核销异常");
        }
        CouponConfirmExceptionPO row = exceptionMapper.selectByUsageNo(usageNo);
        if (row == null || !"PENDING".equals(row.getResolveStatus())) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT, "核销异常已处理过");
        }
        String note = reason == null ? "" : reason.trim();
        if ("RELEASE".equals(normalized)) {
            try {
                var released = tradeTxService.releaseFromException(usageNo, note, LocalDateTime.now());
                return new ResolveResult(usageNo, normalized, UsageStatus.RELEASED.getDesc(),
                        released.couponStatus(), null);
            } catch (TradeCasRejectedException e) {
                throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                        "人工释放失败：状态已被并发变更，请刷新后重试");
            }
        }

        if (row.getPricingId() == null || row.getPricingId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "异常记录没有价格快照，不能按快照原价核销；请核对订单退款后选择 RELEASE");
        }
        SnapshotConfirm snapshot;
        try {
            snapshot = pricingSnapshotService.prepareConfirm(row.getPricingId(), usage,
                    null, true, LocalDateTime.now());
        } catch (BizException e) {
            throw e;   // 快照过期/损坏：只能 RELEASE，错误信息已说明
        }
        FundingSplit split = TradeAppService.calculateFundingSplit(
                snapshot.budgetOwner(), snapshot.budgetRatio(), snapshot.discountAmt());
        List<LineShare> lines = TradeAppService.buildLineShares(snapshot.lineShares(), split);
        try {
            var confirmed = tradeTxService.confirmFromException(usageNo, snapshot.discountAmt(),
                    split, lines, row.getPricingId(), note, LocalDateTime.now());
            return new ResolveResult(usageNo, normalized, UsageStatus.CONFIRMED.getDesc(),
                    confirmed.couponStatus(), confirmed.discountAmt());
        } catch (TradeCasRejectedException e) {
            throw new BizException(ErrorCode.USAGE_STATE_CONFLICT,
                    "人工核销失败：状态已被并发变更，请刷新后重试");
        }
    }

    /** 管理面列表项（不含用户 PII；只给运维定位订单/券） */
    public record PendingException(String usageNo, String appId, long userId, long userCouponId,
                                   String orderNo, String pricingId, String reason,
                                   LocalDateTime createTime) {
    }

    public record ResolveResult(String usageNo, String action, String usageStatus,
                                String couponStatus, Long discountAmt) {
    }
}
