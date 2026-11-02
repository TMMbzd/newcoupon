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
    public Optional<CouponUsage> findByUsageNo(String usageNo, long userId) {
        return Optional.ofNullable(mapper.selectByUsageNoForUser(usageNo, userId))
                .map(CouponUsagePOConverter::toDomain);
    }

    @Override
    public java.util.List<CouponUsage> findByOrderNo(long userId, String orderNo) {
        return mapper.selectByOrderNo(userId, orderNo).stream()
                .map(CouponUsagePOConverter::toDomain)
                .toList();
    }

    @Override
    public Optional<CouponUsage> findByUsageNoForUpdate(String usageNo, long userId) {
        return Optional.ofNullable(mapper.selectByUsageNoForUpdate(usageNo, userId))
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
    public boolean casConfirm(String usageNo, long userId, long discountAmt, LocalDateTime useTime) {
        return mapper.casConfirm(usageNo, userId, discountAmt, useTime) == 1;
    }

    @Override
    public boolean casRelease(String usageNo, long userId, LocalDateTime releaseTime) {
        return mapper.casRelease(usageNo, userId, releaseTime) == 1;
    }

    // ---------- L2 过期关闭（2026-09-12） ----------

    @Override
    public boolean casExpireByUsageNo(String usageNo, long userId) {
        return mapper.casExpireByUsageNo(usageNo, userId) == 1;
    }

    @Override
    public boolean casExpireByCouponId(long userCouponId, long userId) {
        return mapper.casExpireByCouponId(userCouponId, userId) == 1;
    }

    // ---------- L5 锁定超时收场（2026-09-15） ----------

    @Override
    public boolean casTimeoutByUsageNo(String usageNo, long userId) {
        return mapper.casTimeoutByUsageNo(usageNo, userId) == 1;
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
    public boolean casRefundState(String usageNo, long userId,
                                  com.newcoupon.domain.coupon.UsageStatus target) {
        return mapper.casRefundState(usageNo, userId, target.getCode()) == 1;
    }

    // ---------- 环 2：支付后核销异常 ----------

    @Override
    public boolean casConfirmException(String usageNo, long userId) {
        return mapper.casConfirmException(usageNo, userId) == 1;
    }

    @Override
    public boolean casConfirmFromException(String usageNo, long userId,
                                           long discountAmt, LocalDateTime useTime) {
        return mapper.casConfirmFromException(usageNo, userId, discountAmt, useTime) == 1;
    }

    @Override
    public boolean casReleaseFromException(String usageNo, long userId, LocalDateTime releaseTime) {
        return mapper.casReleaseFromException(usageNo, userId, releaseTime) == 1;
    }
}
