package com.newcoupon.domain.pricing;

import java.time.LocalDateTime;

/**
 * 占用单与价格快照的绑定（环 2，V23）。
 *
 * <p>锁券带 {@code pricingId} 时同事务写入；核销不带 ID 时按它自动解析快照，
 * 防止调用方漏传导致重新按当前模板算价。一份占用单只能绑定一份快照。
 */
public record PricingUsage(
        Long id,
        String usageNo,
        String pricingId,
        String appId,
        long userId,
        String orderNo,
        long couponId,
        LocalDateTime boundAt) {
}
