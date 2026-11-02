package com.newcoupon.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量发放任务受理 DTO（V6 阶段 1A；1B 补 scheduleTime）。
 *
 * <p>requestId 校验口径与 {@link ReceiveRequest} 一致（字母/数字/下划线/连字符）：
 * 它参与幂等唯一键，将来若进事件/JSON 载荷，格式受限可以少一层注入面。
 *
 * <p>userIds 的"去重后 ≤ 10000"在服务层校验（注解只能约束原始长度，判不了去重）；
 * 这里只做形态校验：非空、元素非 null 且为正。
 *
 * <p>scheduleTime 语义（V6 阶段 1B 定时发放）：可选的定时开始时间，ISO-8601 本地时间
 * （如 {@code 2026-11-02T10:00:00}）。到期前 worker 不领取该任务的任何 chunk；
 * 到期后自然开跑。传过去的时间等价于立即执行（补跑语义）——不做 @PastOrPresent
 * 硬校验：调用方时钟略快于服务端时，"刚过去几秒"的时间被拒反而制造无谓失败，
 * 而过去时间的补跑语义正是业务想要的。
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
        String operator,

        /** 定时开始时间；null=立即执行。过去时间按立即执行处理（补跑语义）。 */
        LocalDateTime scheduleTime
) {

    /** 兼容 1A 调用方的四参便捷构造（无定时=立即执行）。 */
    public GrantTaskCreateRequest(String requestId, Long templateId,
                                  List<Long> userIds, String operator) {
        this(requestId, templateId, userIds, operator, null);
    }
}
