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
        List<CouponReason> unusableCoupons,

        /**
         * 价格快照 ID（环 2）：带 orderNo 且租户开启快照时返回；
         * 不带 orderNo/未开启时为 null。锁券/核销带上它即可按冻结价格执行。
         */
        String pricingId
) {

    /** 兼容旧调用方/旧测试：不带价格快照的六参构造 */
    public CalculateResponse(Long originalPrice, Long discountAmount, Long finalPrice,
                             List<CouponUsage> usedCoupons, List<LineAllocation> allocations,
                             List<CouponReason> unusableCoupons) {
        this(originalPrice, discountAmount, finalPrice, usedCoupons, allocations,
                unusableCoupons, null);
    }

    /** 同快照重放时回填 pricingId，不改变金额与分摊 */
    public CalculateResponse withPricingId(String id) {
        return new CalculateResponse(originalPrice, discountAmount, finalPrice, usedCoupons,
                allocations, unusableCoupons, id);
    }

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
     *
     * <p>分摊规则（2026-10-18 行业标准版）：每一层优惠按“进入本层前的行剩余金额”比例分摊，
     * 前 n-1 行向下取整、末行兜底余数；单行不得超过该行剩余金额，溢出再分配给有余量的行。
     *
     * <p>{@code couponShares} 是这张行上每一张券各分了多少——按行退款时按“行 × 券”冲回，
     * 不能只有一个合计（多券叠加时合计无法拆回单张券）。
     */
    public record LineAllocation(
            /** 商品行号（订单侧行标识；旧调用方不传时平台生成 L1/L2/...） */
            String lineNo,
            String skuId,
            /** 行金额（单价×数量，分） */
            Long lineAmount,
            /** 该行分摊到的总优惠（分） */
            Long allocatedDiscount,
            /** 该行上每张券的分摊明细（退款按行 × 券冲回） */
            List<CouponLineShare> couponShares
    ) {
        /** 兼容旧调用方/旧测试：没有行号与逐券明细的三参构造 */
        public LineAllocation(String skuId, Long lineAmount, Long allocatedDiscount) {
            this(null, skuId, lineAmount, allocatedDiscount, List.of());
        }
    }

    /** 单行上单张券的分摊额 */
    public record CouponLineShare(Long couponId, Long amount) {
    }
}
