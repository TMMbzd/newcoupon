package com.newcoupon.application.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 价格快照 payload（JSON 文本存 coupon_pricing_snapshot.payload）。
 *
 * <p><b>为什么单独定义而不是直接序列化 CalculateResponse</b>：快照要冻结的不只是
 * “给调用方看的响应”，还包括每张券的模板规则、每券每行分摊、结算主体与扩展决策——
 * 这些是核销时绕过重算的依据。独立记录让“快照结构”可版本化（payloadVersion），
 * 也避免响应 DTO 的兼容构造器污染反序列化。
 *
 * <p><b>必填与审计</b>：行、券集合、每券每行分摊、结算主体是核销必需；
 * 模板面额/门槛/层级/出资是审计与出资拆分依据；扩展决策当前为空列表，
 * 等扩展框架接入后由扩展点写入（结构先冻结，避免以后改 payload 版本）。
 *
 * <p>反序列化忽略未知字段：payload 版本升级时旧代码不会被新字段打挂；
 * 版本号本身不兼容时由应用层 fail-closed。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingSnapshotPayload(
        int version,
        /** 计算/分摊算法版本；算法升级后旧快照仍按旧语义读取 */
        String engineVersion,
        String appId,
        long userId,
        String orderNo,
        String requestDigest,
        /** 生成快照时的结算主体灰度状态（审计；行主体已按它归一） */
        boolean settlePartyEnabled,
        /** 生成快照时生效的优惠占比上限；核销按冻结值判，不读当前配置 */
        int maxDiscountRatio,
        List<Line> lines,
        List<Coupon> coupons,
        Response response,
        List<ExtensionDecision> extensionDecisions,
        LocalDateTime calculatedAt) {

    /** 商品行：核销不传 items 时直接按快照行落分账 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(
            String lineNo,
            String skuId,
            String categoryId,
            long price,
            int quantity,
            long lineAmount,
            String settlePartyId) {
    }

    /**
     * 一张券的快照：applied=true 的券带模板规则与行分摊；false 的券只带不可用原因。
     * couponId 是用户券 ID（与锁券/核销请求里的 couponId 一致）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Coupon(
            long couponId,
            Long templateId,
            String templateCode,
            String templateName,
            String couponType,
            Long faceValue,
            Long discountCap,
            Long threshold,
            String stackingLayer,
            String exclusionGroup,
            String applicableScope,
            String scopeIds,
            String budgetOwner,
            Integer budgetRatio,
            String settlePartyId,
            boolean applied,
            long discountAmount,
            String unusableReason,
            List<LineShare> lineShares) {
    }

    /** 本券在本行分摊到的优惠（行号与 Line.lineNo 对齐） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineShare(String lineNo, long discountAmount) {
    }

    /** 试算响应快照：同摘要重试时原样返回，保证“返回给用户的价格”与快照一致 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(
            long originalPrice,
            long discountAmount,
            long finalPrice,
            List<UsedCoupon> usedCoupons,
            List<Allocation> allocations,
            List<Reason> unusableCoupons) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsedCoupon(long couponId, String templateName, String layer, long discountAmount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Allocation(
            String lineNo,
            String skuId,
            long lineAmount,
            long allocatedDiscount,
            List<CouponShare> couponShares) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CouponShare(long couponId, long amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reason(long couponId, String reason) {
    }

    /**
     * 扩展决策占位：资格/范围/出资等必需扩展的结果。
     * 当前扩展框架尚未接线，列表为空；核销时不得重新调用必需扩展。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtensionDecision(
            String extensionId,
            String version,
            String decisionId,
            String result) {
    }
}
