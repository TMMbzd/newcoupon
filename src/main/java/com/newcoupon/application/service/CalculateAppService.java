package com.newcoupon.application.service;

import com.newcoupon.application.service.discount.DiscountCalculatorService;
import com.newcoupon.application.service.discount.DiscountCalculatorService.CalculationOutcome;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.open.api.dto.CalculateResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 试算编排（环 2）：老调用方走纯计算；带 orderNo 且租户开启快照时，
 * 先按摘要复用快照（命中直接返回冻结响应，不重算），未命中才计算并写快照。
 *
 * <p><b>为什么复用命中不重算</b>：若先重算再返回旧 pricingId，模板在两次调用之间
 * 变化时会出现“响应金额是新的、快照金额是旧的”——核销按快照扣款，用户看到的价
 * 与扣款价不一致。复用必须返回快照里存的那份响应。
 *
 * <p>写快照失败不静默降级：带 orderNo 且开关开启时，拿不到 pricingId 的试算
 * 对调用方没有意义（它以为拿到了价格保证），直接 5000 让调用方重试。
 */
@Service
public class CalculateAppService {

    private final DiscountCalculatorService calculator;
    private final PricingSnapshotService snapshotService;
    private final PricingSnapshotPolicy pricingPolicy;
    private final SettlePartyPolicy settlePartyPolicy;
    /** 试算时生效的优惠占比上限，冻结进快照；核销不再读当前配置 */
    private final int maxDiscountRatio;

    public CalculateAppService(DiscountCalculatorService calculator,
                               PricingSnapshotService snapshotService,
                               PricingSnapshotPolicy pricingPolicy,
                               SettlePartyPolicy settlePartyPolicy,
                               @Value("${coupon.trade.max-discount-ratio:80}") int maxDiscountRatio) {
        this.calculator = calculator;
        this.snapshotService = snapshotService;
        this.pricingPolicy = pricingPolicy;
        this.settlePartyPolicy = settlePartyPolicy;
        this.maxDiscountRatio = maxDiscountRatio;
    }

    public CalculateResponse calculate(CalculateRequest request) {
        String orderNo = normalizeOrderNo(request.orderNo());
        boolean partyEnabled = settlePartyPolicy.isEnabled();

        if (!pricingPolicy.isEnabled() || orderNo == null) {
            return calculator.calculateWithDetails(request, partyEnabled).response();
        }
        if (request.userId() == null || request.couponIds() == null
                || request.couponIds().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "写价格快照必须提供 userId 与券列表");
        }

        String appId = AppOwnership.ownerForNewResource();
        String digest = snapshotService.digest(appId, request, partyEnabled);
        LocalDateTime now = LocalDateTime.now();
        Optional<CalculateResponse> reusable = snapshotService.findReusable(
                digest, appId, request.userId(), orderNo, now);
        if (reusable.isPresent()) {
            return reusable.get();
        }

        CalculationOutcome outcome = calculator.calculateWithDetails(request, partyEnabled, now);
        return snapshotService.create(digest, request, outcome, appId, partyEnabled,
                maxDiscountRatio, now);
    }

    /** 空白 = 不启用快照；超长在 DTO 之外再挡一次（服务可能被直接调用） */
    private static String normalizeOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        if (orderNo.length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "订单号最长 64 字符");
        }
        return orderNo;
    }
}
