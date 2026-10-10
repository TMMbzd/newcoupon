package com.newcoupon.application.service.discount.strategy;

import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;
import org.springframework.stereotype.Component;

/**
 * 无门槛立减策略（Bean 名 = {@link com.newcoupon.domain.coupon.CouponType#NO_THRESHOLD} 枚举名）。
 *
 * <p>规则：门槛恒为 0（模板校验强制），优惠 = min(面额, 订单金额)。
 * 算式与满减相同，但独立成类而非复用满减 Bean——两种类型各自演化时（如立减做活动叠加）
 * 不至于在同一个类里长出 if-else 分叉。
 */
@Component("NO_THRESHOLD")
public class NoThresholdStrategy implements DiscountStrategy {

    @Override
    public Long calculate(UserCoupon coupon, OrderContext context) {
        long faceValue = coupon.getFaceValue();
        long orderAmount = context.getOrderAmount();

        if (orderAmount <= 0) {
            return 0L;
        }
        return Math.min(faceValue, orderAmount);
    }
}
