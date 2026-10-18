package com.newcoupon.infrastructure.persistence;

import com.newcoupon.domain.pricing.PricingUsage;
import com.newcoupon.domain.repository.PricingUsageRepository;
import com.newcoupon.infrastructure.persistence.converter.PricingUsagePOConverter;
import com.newcoupon.infrastructure.persistence.mapper.CouponPricingUsageMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 占用单 ↔ 价格快照绑定仓储适配器（V23）。 */
@Repository
public class PricingUsageRepositoryImpl implements PricingUsageRepository {

    private final CouponPricingUsageMapper mapper;

    public PricingUsageRepositoryImpl(CouponPricingUsageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PricingUsage> findByUsageNo(String usageNo) {
        return Optional.ofNullable(mapper.selectByUsageNo(usageNo))
                .map(PricingUsagePOConverter::toDomain);
    }

    @Override
    public void save(PricingUsage usage) {
        mapper.insert(PricingUsagePOConverter.toPO(usage));
    }
}
