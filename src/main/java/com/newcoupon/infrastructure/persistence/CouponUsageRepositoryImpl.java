package com.newcoupon.infrastructure.persistence;

import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.infrastructure.persistence.converter.CouponUsagePOConverter;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.CouponUsageMapper;
import com.newcoupon.infrastructure.persistence.po.CouponUsagePO;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 占用单仓储适配器 */
@Repository
public class CouponUsageRepositoryImpl implements CouponUsageRepository {

    private final CouponUsageMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    public CouponUsageRepositoryImpl(CouponUsageMapper mapper, SnowflakeIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public Optional<CouponUsage> findByUsageNo(String usageNo) {
        return Optional.ofNullable(mapper.selectByUsageNo(usageNo))
                .map(CouponUsagePOConverter::toDomain);
    }

    @Override
    public CouponUsage save(CouponUsage usage) {
        CouponUsagePO po = CouponUsagePOConverter.toPO(usage);
        po.setId(idGenerator.next());   // 雪花ID（批次二）
        mapper.insert(po);   // 撞 uk_usage_no → DuplicateKeyException（同一订单重复锁同一券）
        return usage.withId(po.getId());
    }

    @Override
    public boolean casConfirm(String usageNo, long discountAmt, LocalDateTime useTime) {
        return mapper.casConfirm(usageNo, discountAmt, useTime) == 1;
    }

    @Override
    public boolean casRelease(String usageNo, LocalDateTime releaseTime) {
        return mapper.casRelease(usageNo, releaseTime) == 1;
    }

    // ---------- L2 过期关闭（2026-09-12） ----------

    @Override
    public boolean casExpireByUsageNo(String usageNo) {
        return mapper.casExpireByUsageNo(usageNo) == 1;
    }

    @Override
    public boolean casExpireByCouponId(long userCouponId) {
        return mapper.casExpireByCouponId(userCouponId) == 1;
    }

    // ---------- L5 锁定超时收场（2026-09-15） ----------

    @Override
    public boolean casTimeoutByUsageNo(String usageNo) {
        return mapper.casTimeoutByUsageNo(usageNo) == 1;
    }

    @Override
    public java.util.List<CouponUsage> selectOverdueOccupying(LocalDateTime threshold, int limit) {
        return mapper.selectOverdueOccupying(threshold, limit).stream()
                .map(CouponUsagePOConverter::toDomain)
                .toList();
    }

    @Override
    public java.util.List<CouponUsage> selectOccupying(int limit) {
        return mapper.selectOccupying(limit).stream()
                .map(CouponUsagePOConverter::toDomain)
                .toList();
    }

    @Override
    public Optional<CouponUsage> findOccupyingByCouponId(long couponId) {
        return Optional.ofNullable(mapper.selectOccupyingByCouponId(couponId))
                .map(CouponUsagePOConverter::toDomain);
    }

    @Override
    public boolean casRefund(String usageNo) {
        return mapper.casRefund(usageNo) == 1;
    }
}
