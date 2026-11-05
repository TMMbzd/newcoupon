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
 *
 * <p><b>V6 4B 审查 m2：CONFIRM 走风控</b>——人工核销是 CONFIRM 状态唯一的资金出口
 * （分账从这里出），绕过决策=拉黑用户的补贴照常分账且零留痕。{@code resolve(CONFIRM)}
 * 前做 decide(scene=CONFIRM)：REJECT 拒绝（运营先处理风控名单再回来人工核销——本端点
 * 就是运营自己的工具，拒绝语义不伤害消费者）；REVIEW/PASS 放行留痕。管理面路径无
 * X-Device-Id（设备规则 degraded 跳过，符合透传语义）；低频管理动作不进熔断段。
 */
@Service
public class ConfirmExceptionService {

    private final CouponConfirmExceptionMapper exceptionMapper;
    private final com.newcoupon.domain.repository.CouponUsageRepository couponUsageRepository;
    private final PricingSnapshotService pricingSnapshotService;
    private final TradeTxService tradeTxService;
    private final RiskDecisionService riskDecisionService;

    public ConfirmExceptionService(CouponConfirmExceptionMapper exceptionMapper,
                                   com.newcoupon.domain.repository.CouponUsageRepository couponUsageRepository,
                                   PricingSnapshotService pricingSnapshotService,
                                   TradeTxService tradeTxService,
                                   RiskDecisionService riskDecisionService) {
        this.exceptionMapper = exceptionMapper;
        this.couponUsageRepository = couponUsageRepository;
        this.pricingSnapshotService = pricingSnapshotService;
        this.tradeTxService = tradeTxService;
        this.riskDecisionService = riskDecisionService;
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
        // V6 4B 审查 m2：人工核销=CONFIRM 的资金出口，进风控（见类注释）。
        // requestId=usageNo（与 confirm 主入口同一幂等锚，决策日志可对照）。
        if ("CONFIRM".equals(normalized)
                && riskDecisionService.enabled(row.getAppId())) {
            RiskDecisionService.RiskDecision risk = riskDecisionService.decide(
                    new com.newcoupon.application.service.RiskContext(
                            com.newcoupon.domain.risk.RiskScene.CONFIRM, row.getAppId(),
                            usage.getUserId(), null, null, usageNo));
            if (risk.action() == com.newcoupon.domain.risk.RiskAction.REJECT) {
                throw new BizException(ErrorCode.RISK_REJECTED,
                        "该用户已被风控拦截，请先处理名单再人工核销");
            }
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
