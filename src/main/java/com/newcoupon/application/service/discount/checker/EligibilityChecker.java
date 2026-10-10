package com.newcoupon.application.service.discount.checker;

import com.newcoupon.common.Result;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;

/**
 * 优惠券可用性校验器接口（责任链模式）。
 *
 * <p>职责：在优惠计算前，校验券是否可用（时间/门槛/状态/适用范围）。
 *
 * <p>设计模式：责任链模式（Chain of Responsibility）
 * - 每个校验器负责一个维度的检查（单一职责）
 * - 校验器之间互不依赖，可独立测试
 * - 编排由上层决定（计算引擎按需串联）
 *
 * <p>实现类示例：
 * <ul>
 *   <li>{@code TimeRangeChecker} - 校验券是否在有效期内
 *   <li>{@code ThresholdChecker} - 校验订单金额是否达到门槛
 *   <li>{@code StatusChecker} - 校验券状态是否为"待使用"
 *   <li>{@code ScopeChecker} - 校验商品是否在适用范围内
 * </ul>
 *
 * <p>典型用法：
 * <pre>{@code
 * Result<Void> result = timeChecker.check(coupon, context);
 * if (!result.isSuccess()) {
 *     return CalculateResponse.ineligible(result.getMessage());
 * }
 * }</pre>
 */
public interface EligibilityChecker {

    /**
     * 校验券是否可用。
     *
     * @param coupon 用户券（包含规则快照：面额、门槛、有效期）
     * @param context 订单上下文（包含订单金额、商品明细、当前时间）
     * @return 校验结果（成功返回 Result.success()，失败返回 Result.fail()）
     */
    Result<Void> check(UserCoupon coupon, OrderContext context);
}
