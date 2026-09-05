package com.newcoupon.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 建券请求 DTO + JSR-303 参数校验。
 *
 * <p>两道校验的分工（都不可省）：
 * <ul>
 *   <li>JSR-303（这里）：**形态校验**——非空、范围、长度。不合法直接 400/参数错误，
 *       根本不进入业务层。这是接口契约的第一道防线，也是全局异常处理器的用武之地；</li>
 *   <li>领域工厂（CouponTemplate.create）：**业务不变量**——满折必填上限、门槛与类型匹配等。
 *       形态合法但业务非法（如满折忘传 discountCap）在这里被拒。</li>
 * </ul>
 * record + 校验注解是 Java 17 的标准做法：不可变、无样板。
 */
public record TemplateCreateRequest(

        @NotBlank(message = "券名称不能为空")
        @Size(max = 128, message = "券名称最长 128 字符")
        String name,

        @NotNull(message = "activityId 不能为空")
        @Min(value = 1, message = "activityId 必须为正")
        Long activityId,

        @NotNull(message = "券类型不能为空（1满减 2满折 3无门槛）")
        @Min(1) @Max(3)
        Integer couponType,

        @NotNull(message = "面额不能为空")
        @Min(value = 1, message = "面额必须为正（满减/无门槛=分，满折=折扣基点 8500 表示 85 折）")
        Long faceValue,

        @Min(value = 1, message = "满折优惠上限必须为正（单位分）")
        Long discountCap,          // 满折必填（业务校验在领域层），其余类型必须不传

        @Min(value = 0, message = "门槛不能为负")
        Long threshold,

        @NotNull(message = "总库存不能为空")
        @Min(value = 1, message = "总库存必须为正")
        Long totalCount,

        @NotNull(message = "每人限领不能为空")
        @Min(1)
        Integer perUserLimit,

        @NotNull(message = "有效期类型不能为空（1固定区间 2领取后N天）")
        Integer validType,

        LocalDateTime validStart,  // validType=1 必填（领域层校验）
        LocalDateTime validEnd,
        Integer validDays,         // validType=2 必填（领域层校验）

        @Min(value = 0, message = "批次日限不能为负（0=不限）")
        Long dailyLimit,           // null 视为 0

        @Min(value = 0, message = "每人日限不能为负（0=不限）")
        Integer perUserDailyLimit  // null 视为 0
) {
}
