package com.newcoupon.domain.model.discount;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单上下文：封装订单信息，用于优惠计算。
 *
 * <p>职责：在计算优惠时传递订单的完整上下文（金额、商品明细、用户、时间）。
 * 校验器和策略器通过本类获取订单信息，判断优惠规则是否适用、计算优惠金额。
 *
 * <p>设计要点：
 * <ul>
 *   <li>不可变对象（final 字段 + 私有构造）——传递过程中不会被篡改；
 *   <li>Builder 模式——构造清晰，字段多时不易出错；
 *   <li>内部类 OrderItem——封装商品明细，支持品类/SKU 级别的适用范围判断。
 * </ul>
 *
 * <p>典型用法：
 * <pre>{@code
 * OrderContext context = OrderContext.builder()
 *     .userId(10001L)
 *     .orderAmount(50000L)  // 500.00 元
 *     .items(List.of(
 *         new OrderItem("SKU001", "C001", 10000L, 2),
 *         new OrderItem("SKU002", "C002", 15000L, 2)
 *     ))
 *     .now(LocalDateTime.now())
 *     .build();
 * }</pre>
 */
public final class OrderContext {

    private final Long userId;
    private final Long orderAmount;
    private final List<OrderItem> items;
    private final LocalDateTime now;

    private OrderContext(Long userId, Long orderAmount, List<OrderItem> items, LocalDateTime now) {
        this.userId = userId;
        this.orderAmount = orderAmount;
        this.items = items;
        this.now = now;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getUserId() {
        return userId;
    }

    public Long getOrderAmount() {
        return orderAmount;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public LocalDateTime getNow() {
        return now;
    }

    /**
     * 计算订单商品总金额（基于 items 累加）。
     * <p>注意：与 {@link #orderAmount} 的区别——
     * <ul>
     *   <li>{@code orderAmount}：前端传入的订单总金额（可能已扣运费/已扣其他优惠）；
     *   <li>{@code getTotalAmount()}：商品明细的累加值（纯商品价格 * 数量）。
     * </ul>
     * <p>业务逻辑应根据实际需求选择使用哪个金额字段。
     *
     * @return 商品总金额（分）
     */
    public Long getTotalAmount() {
        if (items == null || items.isEmpty()) {
            return 0L;
        }
        return items.stream()
                .mapToLong(item -> item.getPrice() * item.getQuantity())
                .sum();
    }

    /**
     * 订单商品明细。
     *
     * <p>字段说明：
     * <ul>
     *   <li>skuId：商品 SKU 编码（用于判断"指定 SKU 可用"）；
     *   <li>categoryId：品类编码（用于判断"指定品类可用"）；
     *   <li>price：单价（分）；
     *   <li>quantity：数量。
     * </ul>
     */
    public static final class OrderItem {
        private final String skuId;
        private final String categoryId;
        private final Long price;
        private final Integer quantity;

        public OrderItem(String skuId, String categoryId, Long price, Integer quantity) {
            this.skuId = skuId;
            this.categoryId = categoryId;
            this.price = price;
            this.quantity = quantity;
        }

        public String getSkuId() {
            return skuId;
        }

        public String getCategoryId() {
            return categoryId;
        }

        public Long getPrice() {
            return price;
        }

        public Integer getQuantity() {
            return quantity;
        }
    }

    public static final class Builder {
        private Long userId;
        private Long orderAmount;
        private List<OrderItem> items;
        private LocalDateTime now;

        private Builder() {
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder orderAmount(Long orderAmount) {
            this.orderAmount = orderAmount;
            return this;
        }

        public Builder items(List<OrderItem> items) {
            this.items = items;
            return this;
        }

        public Builder now(LocalDateTime now) {
            this.now = now;
            return this;
        }

        public OrderContext build() {
            return new OrderContext(userId, orderAmount, items, now);
        }
    }
}
