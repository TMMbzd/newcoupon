package com.newcoupon.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量发放任务受理 DTO（V6 阶段 1A）。
 *
 * <p>requestId 校验口径与 {@link ReceiveRequest} 一致（字母/数字/下划线/连字符）：
 * 它参与幂等唯一键，将来若进事件/JSON 载荷，格式受限可以少一层注入面。
 *
 * <p>userIds 的"去重后 ≤ 10000"在服务层校验（注解只能约束原始长度，判不了去重）；
 * 这里只做形态校验：非空、元素非 null 且为正。
 */
public record GrantTaskCreateRequest(

        @NotBlank(message = "requestId 不能为空（重试时请复用同一个）")
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}",
                message = "requestId 仅限字母/数字/下划线/连字符")
        String requestId,

        @NotNull(message = "templateId 不能为空")
        @Min(1)
        Long templateId,

        @NotEmpty(message = "userIds 不能为空")
        List<@NotNull(message = "userId 不能为 null") @Min(value = 1, message = "userId 必须为正") Long> userIds,

        @Size(max = 64, message = "操作人最长 64 字符")
        String operator
) {
}
