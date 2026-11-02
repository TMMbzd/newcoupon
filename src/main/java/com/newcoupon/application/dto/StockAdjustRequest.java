package com.newcoupon.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * C1 库存调整请求（ADR-0018 §3.6）。
 *
 * <p>{@code expectedTotalCount} 是乐观并发条件：调用方先读到当前总量，再提交目标总量。
 * 两个管理员同时调整时，只有一个能把“预期值”对上，另一个收到状态冲突而不是互相覆盖。
 * {@code targetTotalCount} 不能低于已发数量（{@code SUM(used)}）；已发券不会被减库存抹掉。
 */
public record StockAdjustRequest(

        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64)
        String requestId,

        @NotNull(message = "expectedTotalCount 不能为空")
        @Min(value = 0, message = "expectedTotalCount 不能为负")
        Long expectedTotalCount,

        @NotNull(message = "targetTotalCount 不能为空")
        @Min(value = 0, message = "targetTotalCount 不能为负")
        Long targetTotalCount,

        @Size(max = 255, message = "调整原因最长 255 字符")
        String reason,

        @Size(max = 64, message = "操作人最长 64 字符")
        String operator
) {
}
