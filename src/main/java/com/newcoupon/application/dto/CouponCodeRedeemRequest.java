package com.newcoupon.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 兑换码兑换请求 DTO（V6 阶段 1B）。
 *
 * <p>requestId 上限 50（比别处窄）：它要加前缀派生 receive 幂等键
 * {@code redeem-{requestId}}，而 user_coupon.idempotent_key 列宽 64——
 * 50+7=57 才放得下。网络超时重试必须复用同一个 requestId。
 *
 * <p>code 原样传用户输入（大小写/易混字符由服务端规范化）；
 * 长度上限 64 只是入参护栏，真实合法长度是 16/26。
 */
public record CouponCodeRedeemRequest(

        @NotBlank(message = "requestId 不能为空（重试时请复用同一个）")
        @Size(max = 50, message = "requestId 最长 50 字符（服务端要加 redeem- 前缀派生幂等键）")
        @Pattern(regexp = "[A-Za-z0-9_\\-]{1,50}",
                message = "requestId 仅限字母/数字/下划线/连字符")
        String requestId,

        @NotBlank(message = "code 不能为空")
        @Size(max = 64, message = "code 最长 64 字符")
        String code,

        @NotNull(message = "userId 不能为空")
        @Min(1)
        Long userId
) {
}
