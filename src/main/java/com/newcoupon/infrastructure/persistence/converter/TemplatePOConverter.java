package com.newcoupon.infrastructure.persistence.converter;

import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;

/**
 * DO ↔ PO 转换器。转换只做"搬运 + 枚举/code 映射"，禁止夹带业务逻辑——
 * 一旦转换器开始 if-else 业务分支，说明领域模型漏了概念（该回到领域层补，而不是在这里打补丁）。
 * 工业界惯例：字段多了用 MapStruct（编译期生成、零反射），这里手写让每行可见。
 */
public final class TemplatePOConverter {

    private TemplatePOConverter() {
    }

    /** 领域对象 → PO（用于 insert）。id/时间由数据库与 MyBatis-Plus 负责回填 */
    public static CouponTemplatePO toPO(CouponTemplate d) {
        CouponTemplatePO p = new CouponTemplatePO();
        p.setId(d.getId());
        p.setTemplateCode(d.getTemplateCode());
        p.setActivityId(d.getActivityId());
        p.setName(d.getName());
        p.setCouponType(d.getCouponType().getCode());
        p.setFaceValue(d.getFaceValue());
        p.setDiscountCap(d.getDiscountCap());
        p.setThreshold(d.getThreshold());
        p.setTotalCount(d.getTotalCount());
        p.setIssuedCount(0L);
        p.setPerUserLimit(d.getPerUserLimit());
        p.setDailyLimit(d.getDailyLimit());
        p.setPerUserDailyLimit(d.getPerUserDailyLimit());
        p.setValidType(d.getValidType().getCode());
        p.setValidStart(d.getValidStart());
        p.setValidEnd(d.getValidEnd());
        p.setValidDays(d.getValidDays());
        p.setApproveStatus(d.getApproveStatus().getCode());
        p.setPublishStatus(d.getPublishStatus().getCode());
        p.setAppId(d.getAppId());   // S1 多租户归属
        p.setVersion(0);
        return p;
    }

    /**
     * PO → 领域对象（查询回读）。
     * 用 reconstitute（重建）而非 create（创建）：create 会把状态重置为"已审批+未发布"，
     * 状态必须以 DB 为准；枚举 code 用 of() 反查，脏值第一时间抛错（反序列化不可信原则）。
     */
    public static CouponTemplate toDomain(CouponTemplatePO p) {
        return CouponTemplate.reconstitute(
                p.getId(),
                CouponTemplate.Creation.builder()
                        .templateCode(p.getTemplateCode())
                        .activityId(p.getActivityId())
                        .name(p.getName())
                        .couponType(CouponType.of(p.getCouponType()))
                        .faceValue(p.getFaceValue())
                        .discountCap(p.getDiscountCap())
                        .threshold(p.getThreshold())
                        .totalCount(p.getTotalCount())
                        .perUserLimit(p.getPerUserLimit())
                        .dailyLimit(p.getDailyLimit() == null ? 0L : p.getDailyLimit())
                        .perUserDailyLimit(p.getPerUserDailyLimit() == null ? 0 : p.getPerUserDailyLimit())
                        .validType(ValidType.of(p.getValidType()))
                        .validStart(p.getValidStart())
                        .validEnd(p.getValidEnd())
                        .validDays(p.getValidDays())
                        .appId(p.getAppId())
                        .build(),
                p.getIssuedCount(),
                ApproveStatus.of(p.getApproveStatus()),
                PublishStatus.of(p.getPublishStatus()));
    }
}
