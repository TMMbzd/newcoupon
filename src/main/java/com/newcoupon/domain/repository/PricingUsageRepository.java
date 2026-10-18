package com.newcoupon.domain.repository;

import com.newcoupon.domain.pricing.PricingUsage;
import java.util.Optional;

/** 占用单 ↔ 价格快照绑定仓储端口（环 2）。 */
public interface PricingUsageRepository {

    Optional<PricingUsage> findByUsageNo(String usageNo);

    /** 同事务写入；撞 uk_pricing_usage 抛 DuplicateKeyException */
    void save(PricingUsage usage);
}
