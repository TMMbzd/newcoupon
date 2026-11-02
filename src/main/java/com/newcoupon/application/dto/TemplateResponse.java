package com.newcoupon.application.dto;

import com.newcoupon.domain.coupon.CouponTemplate;
import java.time.LocalDateTime;

/**
 * 模板详情响应 DTO。只暴露外部需要的字段——不把领域对象/PO 直接序列化出去：
 * 内部字段结构是实现的细节，直接暴露=每次内部改动都是对外契约变更（且容易泄露内部语义）。
 */
public record TemplateResponse(
        Long id,
        String templateCode,
        String name,
        String couponType,
        Long faceValue,
        Long discountCap,
        Long threshold,
        Long totalCount,
        Long issuedCount,
        Integer perUserLimit,
        Long dailyLimit,
        Integer perUserDailyLimit,
        String validType,
        LocalDateTime validStart,
        LocalDateTime validEnd,
        Integer validDays,
        String approveStatus,
        String publishStatus,
        LocalDateTime createTime,
        // ---- 五维模型后四维（第 2 环补运营入口，2026-10-15）：写进去要能读回来 ----
        String applicableScope,    // 全场/品类/单品
        String scopeIds,           // 范围值（JSON 数组文本）
        String stackingLayer,      // 店铺级/平台级
        String exclusionGroup,     // 互斥组名（null=无组）
        String budgetOwner,        // 平台/商家/共担
        Integer budgetRatio,       // 共担时平台百分比
        String settlePartyId,      // 结算主体（可空=平台券租户级）
        Integer stockShardCount) { // C1 实际分片数 N_eff

    public static TemplateResponse from(CouponTemplate t) {
        return new TemplateResponse(
                t.getId(), t.getTemplateCode(), t.getName(),
                t.getCouponType().getDesc(), t.getFaceValue(), t.getDiscountCap(), t.getThreshold(),
                t.getTotalCount(), t.getIssuedCount(), t.getPerUserLimit(),
                t.getDailyLimit(), t.getPerUserDailyLimit(),
                t.getValidType().getDesc(), t.getValidStart(), t.getValidEnd(), t.getValidDays(),
                t.getApproveStatus().getDesc(), t.getPublishStatus().getDesc(),
                null,   // createTime 由 DB 生成，查询场景需要时从 PO 侧带出
                t.getApplicableScope().getDesc(), t.getScopeIds(),
                t.getStackingLayer().getDesc(), t.getExclusionGroup(),
                t.getBudgetOwner().getDesc(), t.getBudgetRatio(), t.getSettlePartyId(),
                t.getStockShardCount());
    }
}
