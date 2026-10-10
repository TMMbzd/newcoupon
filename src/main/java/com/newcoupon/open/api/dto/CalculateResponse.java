package com.newcoupon.open.api.dto;

import java.util.List;

/**
 * 优惠计算响应 DTO。
 *
 * <p>职责：返回计算引擎的择优结果——哪些券用上了、优惠了多少、哪些券不可用及原因。
 *
 * <p>为什么不用 @Data 而用 record？
 * <ul>
 *   <li>响应 DTO 应该不可变（防止被业务代码意外修改后序列化出去）；</li>
 *   <li>record 天然不可变、自带 equals/hashCode/toString，零样板；</li>
 *   <li>Java 17 的标准做法，Spring Boot 3.x 原生支持。</li>
 * </ul>
 */
public record CalculateResponse(

        /**
         * 订单原价（分）。
         */
        Long originalPrice,

        /**
         * 总优惠金额（分）。
         */
        Long discountAmount,

        /**
         * 最终应付金额（分）= 原价 - 优惠金额。
         */
        Long finalPrice,

        /**
         * 实际使用的优惠券列表（按优惠金额降序）。
         */
        List<CouponUsage> usedCoupons,

        /**
         * 不可用的优惠券及原因。
         */
        List<CouponReason> unusableCoupons
) {

    /**
     * 优惠券使用明细。
     *
     * <p>为什么内嵌？
     * <ul>
     *   <li>只在 CalculateResponse 里用到，不会被其他模块依赖；</li>
     *   <li>作为聚合根的一部分，内嵌更清晰。</li>
     * </ul>
     */
    public record CouponUsage(

            /**
             * 优惠券ID。
             */
            Long couponId,

            /**
             * 券模板名称（便于前端展示"已使用：满100减20元"）。
             */
            String templateName,

            /**
             * 本张券的优惠金额（分）。
             */
            Long discountAmount
    ) {
    }

    /**
     * 优惠券不可用原因。
     *
     * <p>典型原因：
     * <ul>
     *   <li>"未到使用时间"</li>
     *   <li>"已过期"</li>
     *   <li>"未满足门槛：订单金额99元，券需满100元"</li>
     *   <li>"商品不在适用范围"</li>
     *   <li>"券已使用"</li>
     *   <li>"券已冻结"</li>
     * </ul>
     */
    public record CouponReason(

            /**
             * 优惠券ID。
             */
            Long couponId,

            /**
             * 不可用原因（人类可读）。
             */
            String reason
    ) {
    }
}
