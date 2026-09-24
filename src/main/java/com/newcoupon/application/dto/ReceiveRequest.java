package com.newcoupon.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 领券请求 DTO。
 *
 * <p>requestId 语义（study/01a Q10）：调用方（前端/上游服务）为"一次领取意图"生成的编号，
 * 网络超时重试时必须复用同一个——它配合服务端唯一索引保证"重试不重发"。
 *
 * <p>⚠️ 已登记简化（STATUS L12）：userId 由请求携带（无登录态）；ROADMAP M6 接入登录态后
 * 改为从 token 解析，**绝不信任参数里的身份**（现在是"没有登录态"而不是"身份可以伪造"）。
 */
public record ReceiveRequest(

        @NotBlank(message = "requestId 不能为空（网络重试时请复用同一个）")
        @Size(max = 64)
        @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}",
                message = "requestId 仅限字母/数字/下划线/连字符（outbox payload JSON 契约安全——终审 P2）")
        String requestId,

        @NotNull(message = "userId 不能为空")
        @Min(1)
        Long userId,

        @NotNull(message = "templateId 不能为空")
        @Min(1)
        Long templateId
) {
}
