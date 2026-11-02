package com.newcoupon.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 兑换码批次受理 DTO（V6 阶段 1B）。
 *
 * <p>requestId 参与幂等唯一键 uk(app_id, request_id)，格式口径与
 * {@link ReceiveRequest} 一致。
 *
 * <p>count 上限 10 万：单事务建批的规模护栏（10 万行码 × 分批 500 插入可控；
 * 更大规模应分多次建批，而不是放大单事务）。
 */
public record CouponCodeBatchCreateRequest(

        @NotBlank(message = "requestId 不能为空（重试时请复用同一个）")
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}",
                message = "requestId 仅限字母/数字/下划线/连字符")
        String requestId,

        @NotNull(message = "templateId 不能为空")
        @Min(1)
        Long templateId,

        @NotNull(message = "count 不能为空")
        @Min(value = 1, message = "count 至少为 1")
        @Max(value = 100_000, message = "count 单批不得超过 100000（更大规模请分批）")
        Integer count,

        @NotNull(message = "codeType 不能为空")
        @Min(value = 1, message = "codeType：1=手工码 2=二维码")
        @Max(value = 2, message = "codeType：1=手工码 2=二维码")
        Integer codeType,

        @Size(max = 64, message = "操作人最长 64 字符")
        String operator
) {
}
