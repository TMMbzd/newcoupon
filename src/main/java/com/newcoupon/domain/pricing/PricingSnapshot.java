package com.newcoupon.domain.pricing;

import java.time.LocalDateTime;

/**
 * 试算价格快照（环 2）。
 *
 * <p>一次带 {@code orderNo} 的试算 = 一份快照，冻结这一单的商品行、券集合、
 * 每张券每行的分摊、结算主体、模板规则摘要与扩展决策。核销带 {@code pricingId}
 * 时只读快照，不再按当前模板重算。
 *
 * <p>payload 以 JSON 文本保存（不把 Jackson 依赖带进领域层）；解析与校验在应用服务。
 * 快照不可修改：要改只能重新试算生成新 ID。
 *
 * <p><b>liveDigest</b>：ACTIVE 时等于 requestDigest；过期/撤销后置 null。
 * 唯一索引允许多个 null，所以同摘要过期重建会产生新行（新 pricingId），
 * 历史行保留审计，不会被迫删旧快照。
 */
public record PricingSnapshot(
        Long id,
        String pricingId,
        String appId,
        long userId,
        String orderNo,
        /** 快照中实际可用的券 ID，升序逗号分隔；与占用单集合做 L70 一致性校验 */
        String couponSet,
        /** 请求摘要（canonical SHA-256） */
        String requestDigest,
        /** ACTIVE 摘要；过期后为 null */
        String liveDigest,
        String payload,
        int payloadVersion,
        /** 计算/分摊算法版本 */
        String engineVersion,
        int payloadBytes,
        int lineCount,
        int appliedCouponCount,
        long originalAmount,
        long discountAmount,
        long finalAmount,
        LocalDateTime expiresAt,
        LocalDateTime lastBoundAt,
        LocalDateTime createTime) {

    public boolean expiredAt(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}
