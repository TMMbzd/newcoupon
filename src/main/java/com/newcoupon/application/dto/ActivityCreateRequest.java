package com.newcoupon.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 创建营销活动请求（V6 阶段 1C，内部面）。JSR-303 只管形态；"开始早于结束"、
 * "开预算必须有正预算额"在服务层校验。
 *
 * <p>budgetEnabled 默认关：活动先建、预算闸门后开（运营确认口径再开），
 * 与存量发放链路"默认零变化"的回滚承诺一致。
 */
public record ActivityCreateRequest(

        @NotBlank(message = "活动名称不能为空")
        @Size(max = 128, message = "活动名称最长 128 字符")
        String name,

        @NotNull(message = "预算金额不能为空（分）")
        @Min(value = 1, message = "预算金额必须为正（分）")
        Long budgetAmount,

        @NotNull(message = "budgetEnabled 不能为空（true=开启预算闸门）")
        Boolean budgetEnabled,

        @NotNull(message = "开始时间不能为空")
        LocalDateTime startTime,

        @NotNull(message = "结束时间不能为空")
        LocalDateTime endTime,

        @Size(max = 64, message = "操作人最长 64 字符")
        String operator
) {
}
