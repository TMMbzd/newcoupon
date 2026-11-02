package com.newcoupon.application.dto;

/** C1 库存调整结果；{@code idempotent=true} 表示同一 requestId 已执行过，本次未重复改额度。 */
public record StockAdjustResult(
        long templateId,
        long totalCount,
        long usedCount,
        boolean idempotent
) {
}
