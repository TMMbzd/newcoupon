package com.newcoupon.application.service.discount.strategy;

import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;
import org.springframework.stereotype.Component;

/**
 * 满折策略（Bean 名 = {@link com.newcoupon.domain.coupon.CouponType#DISCOUNT} 枚举名）。
 *
 * <p>规则：面额存折扣基点（9000 = 9 折）；
 * 优惠 = 订单金额 × (10000 - 折扣率) / 10000，四舍五入到整数分；
 * 有封顶取 min；最终不超过订单金额（防负数订单）。
 *
 * <p>金额纪律：全整数运算——(amount × rate + 5000) / 10000 是"加半再整除"的四舍五入，
 * 与 Math.round((double)…) 数值等价，但全程 long，不引入浮点（金额用 double 是资损隐患）。
 */
@Component("DISCOUNT")
public class DiscountRateStrategy implements DiscountStrategy {

    @Override
    public Long calculate(UserCoupon coupon, OrderContext context) {
        long discountRate = coupon.getFaceValue();
        long orderAmount = context.getOrderAmount();

        if (orderAmount <= 0) {
            return 0L;
        }
        // 折扣率异常防御（数据脏了直接判不可用，不猜）
        if (discountRate <= 0 || discountRate >= 10000) {
            return 0L;
        }

        // 四舍五入到整数分：加半个分母再整除（例：10000 分 × (10000-9000) = 1000 分）
        long discountAmount = (orderAmount * (10000 - discountRate) + 5000) / 10000;

        Long cap = coupon.getDiscountCap();
        if (cap != null && cap > 0) {
            discountAmount = Math.min(discountAmount, cap);
        }

        return Math.min(discountAmount, orderAmount);
    }
}
