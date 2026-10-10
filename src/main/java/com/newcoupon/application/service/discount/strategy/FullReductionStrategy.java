package com.newcoupon.application.service.discount.strategy;

import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;
import org.springframework.stereotype.Component;

/**
 * 满减策略（Bean 名 = {@link com.newcoupon.domain.coupon.CouponType#FULL_REDUCTION} 枚举名）。
 *
 * <p>规则：面额即减免额；优惠 = min(面额, 订单金额)——上限防"优惠超过订单"的负数订单。
 * 门槛校验在责任链（ThresholdChecker），本策略只管计算。
 */
@Component("FULL_REDUCTION")
public class FullReductionStrategy implements DiscountStrategy {

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
