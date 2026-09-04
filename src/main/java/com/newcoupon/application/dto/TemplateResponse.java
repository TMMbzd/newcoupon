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
        String validType,
        LocalDateTime validStart,
        LocalDateTime validEnd,
        Integer validDays,
        String approveStatus,
        String publishStatus,
        LocalDateTime createTime) {

    public static TemplateResponse from(CouponTemplate t) {
        return new TemplateResponse(
                t.getId(), t.getTemplateCode(), t.getName(),
                t.getCouponType().getDesc(), t.getFaceValue(), t.getDiscountCap(), t.getThreshold(),
                t.getTotalCount(), t.getIssuedCount(), t.getPerUserLimit(),
                t.getValidType().getDesc(), t.getValidStart(), t.getValidEnd(), t.getValidDays(),
                t.getApproveStatus().getDesc(), t.getPublishStatus().getDesc(),
                null);   // createTime 由 DB 生成，查询场景需要时从 PO 侧带出
    }
}
