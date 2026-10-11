package com.newcoupon.open.api.dto;

import java.util.List;

/**
 * 优惠计算响应 DTO（第 2 环升级：两层叠加 + 行级分摊）。
 *
 * <p>变更点（相对环 0 版本）：
 * <ul>
 *   <li>CouponUsage 新增 layer 字段（SHOP/PLATFORM）——跨层可叠加（各减各的），同层互斥（选最优）</li>
 *   <li>新增 allocations 列表——每行商品分摊到的优惠金额（按比例分摊 + 末行兜底，合计=总优惠）</li>
 * </ul>
 */
public record CalculateResponse(

        /** 订单原价（分） */
        Long originalPrice,

        /** 总优惠金额（分）= 各层最优券优惠之和（不超过原价） */
        Long discountAmount,

        /** 最终应付金额（分）= 原价 - 总优惠 */
        Long finalPrice,

        /** 实际使用的优惠券（最多 2 张：每层各选最优 1 张） */
        List<CouponUsage> usedCoupons,

        /** 各商品行分摊到的优惠金额（分摊合计 = 总优惠，资损防线） */
        List<LineAllocation> allocations,

        /** 不可用的优惠券及原因 */
        List<CouponReason> unusableCoupons
) {

    /** 优惠券使用明细（含叠加层级） */
    public record CouponUsage(
            Long couponId,
            String templateName,
            /** 叠加层级：SHOP(店铺级) / PLATFORM(平台级) */
            String layer,
            Long discountAmount
    ) {
    }

    /** 不可用券及原因 */
    public record CouponReason(
            Long couponId,
            String reason
    ) {
    }

    /**
     * 单行商品的优惠分摊。
     * 分摊规则：按行金额占总金额的比例分摊，前 n-1 行整除取整，末行 = 总优惠 - 前面之和（保证合计精确）。
     */
    public record LineAllocation(
            String skuId,
            /** 行金额（单价×数量，分） */
            Long lineAmount,
            /** 该行分摊到的总优惠（分） */
            Long allocatedDiscount
    ) {
    }
}
