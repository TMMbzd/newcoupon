package com.newcoupon.application.dto;

import com.newcoupon.infrastructure.persistence.po.CouponActivityPO;
import java.time.LocalDateTime;

/**
 * 营销活动响应（V6 阶段 1C）。budgetEnabled 是调用方最关心的位——
 * 它决定这个活动下的模板发券要不要过预算闸门。
 */
public record ActivityResponse(
        Long id,
        String activityCode,
        String name,
        Long budgetAmount,
        boolean budgetEnabled,
        String appId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer status
) {
    public static ActivityResponse from(CouponActivityPO po) {
        return new ActivityResponse(po.getId(), po.getActivityCode(), po.getName(),
                po.getBudgetAmount(),
                po.getBudgetEnabled() != null && po.getBudgetEnabled() == CouponActivityPO.BUDGET_ENABLED,
                po.getAppId(), po.getStartTime(), po.getEndTime(), po.getStatus());
    }
}
