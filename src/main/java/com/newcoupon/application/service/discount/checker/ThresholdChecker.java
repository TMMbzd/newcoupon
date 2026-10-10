package com.newcoupon.application.service.discount.checker;

import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 门槛校验器（责任链第 3 环，@Order(300)）：订单金额是否达到券的使用门槛。
 *
 * <p>为什么从用户券（快照）而不是模板读门槛：券在领取时已固化规则快照，
 * 模板后续变更不影响已发券（防"历史券变性"资损）。
 */
@Component
@Order(300)
public class ThresholdChecker implements EligibilityChecker {

    @Override
    public Result<Void> check(UserCoupon coupon, OrderContext context) {
        if (coupon == null || context == null) {
            // 形态问题才是参数错误；业务不可用走 COUPON_NOT_USABLE——两个码域不混用
            return Result.fail(ErrorCode.PARAM_ERROR, "券或订单上下文为空");
        }

        long threshold = coupon.getThreshold();
        Long orderAmount = context.getOrderAmount();
        if (orderAmount == null) {
            return Result.fail(ErrorCode.PARAM_ERROR, "订单金额不能为空");
        }

        if (orderAmount < threshold) {
            return Result.fail(ErrorCode.COUPON_NOT_USABLE,
                    String.format("订单金额未达使用门槛（需满%.2f元）", threshold / 100.0));
        }
        return Result.success();
    }
}
