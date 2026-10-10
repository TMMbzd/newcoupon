package com.newcoupon.application.service.discount.strategy;

import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;

/**
 * 优惠计算策略接口：定义单张券的优惠金额计算契约。
 *
 * <p>职责：根据券类型（满减/折扣/立减）和订单上下文，计算该券能优惠多少钱。
 * 每个具体策略（FullReductionStrategy、DiscountStrategy、InstantStrategy）
 * 实现一种券类型的计算逻辑，由计算引擎根据券类型动态选择策略。
 *
 * <p>设计模式：策略模式（Strategy Pattern）。
 * - 策略接口：本接口定义 {@link #calculate} 契约；
 * - 具体策略：各券类型的计算器（满减/折扣/立减）；
 * - 上下文：计算引擎（DiscountCalculator）持有策略 Map，根据券类型选择策略。
 *
 * <p>技术实现：
 * - 具体策略类用 {@code @Component("FULL_REDUCTION")} 注解，Bean 名称 = 券类型枚举名；
 * - 计算引擎通过 {@code Map<String, DiscountStrategy>} 注入全部策略（Spring 自动按类型收集）；
 * - 运行时根据 {@code coupon.getTemplate().getDiscountType().name()} 获取对应策略。
 *
 * <p>典型用法：
 * <pre>{@code
 * @Component
 * public class DiscountCalculator {
 *     private final Map<String, DiscountStrategy> strategies;
 *
 *     public DiscountCalculator(Map<String, DiscountStrategy> strategies) {
 *         this.strategies = strategies;
 *     }
 *
 *     public Long calculate(UserCoupon coupon, OrderContext context) {
 *         String type = coupon.getTemplate().getDiscountType().name();
 *         DiscountStrategy strategy = strategies.get(type);
 *         return strategy.calculate(coupon, context);
 *     }
 * }
 * }</pre>
 */
public interface DiscountStrategy {

    /**
     * 计算单张券的优惠金额。
     *
     * <p>入参说明：
     * - coupon：用户持有的券（包含规则快照：面额、门槛、封顶）；
     * - context：订单上下文（订单金额、商品明细、用户、时间）。
     *
     * <p>返回值：优惠金额（分）。
     * - 返回 0：券不可用（未达门槛/订单金额为 0 等）；
     * - 返回正数：实际优惠金额（已考虑封顶/订单金额上限）。
     *
     * <p>注意事项：
     * 1. 本方法<b>不校验</b>券的状态/时间/适用范围——这些由责任链校验器（Checker）完成；
     * 2. 本方法<b>只计算</b>优惠金额，不修改券状态、不扣库存；
     * 3. 计算结果不能超过订单金额（防止负数订单）。
     *
     * @param coupon  用户券（包含规则快照）
     * @param context 订单上下文
     * @return 优惠金额（分），0 表示不可用
     */
    Long calculate(UserCoupon coupon, OrderContext context);
}
