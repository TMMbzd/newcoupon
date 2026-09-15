package com.newcoupon.infrastructure.persistence;

import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.converter.UserCouponPOConverter;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.persistence.mapper.UserCouponMapper;
import com.newcoupon.infrastructure.persistence.po.UserCouponPO;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 用户券仓储适配器 */
@Repository
public class UserCouponRepositoryImpl implements UserCouponRepository {

    private final UserCouponMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    public UserCouponRepositoryImpl(UserCouponMapper mapper, SnowflakeIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public Optional<UserCoupon> findByIdempotentKey(long userId, String idempotentKey) {
        return Optional.ofNullable(mapper.selectByIdempotentKey(userId, idempotentKey))
                .map(UserCouponPOConverter::toDomain);
    }

    @Override
    public long countByTemplate(long templateId) {
        return mapper.countByTemplate(templateId);
    }

    @Override
    public Optional<UserCoupon> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id))
                .map(UserCouponPOConverter::toDomain);
    }

    @Override
    public List<UserCoupon> findByUser(long userId, CouponStatus status) {
        return mapper.selectByUser(userId, status == null ? null : status.getCode()).stream()
                .map(UserCouponPOConverter::toDomain)
                .toList();
    }

    @Override
    public UserCoupon save(UserCoupon userCoupon) {
        UserCouponPO po = UserCouponPOConverter.toPO(userCoupon, userCoupon.getReceiveTime());
        po.setId(idGenerator.next());   // 雪花ID（批次二）：分库后自增跨库重复，趋势递增保B+树
        mapper.insert(po);   // 撞 uk_idem（幂等）/ uk_user_seq（限领N兜底）→ DuplicateKeyException 抛给上层
        return userCoupon.withId(po.getId());
    }

    @Override
    public boolean casLock(long userCouponId) {
        return mapper.casLock(userCouponId) == 1;
    }

    @Override
    public boolean casConfirm(long userCouponId) {
        return mapper.casConfirm(userCouponId) == 1;
    }

    @Override
    public boolean casRelease(long userCouponId) {
        return mapper.casRelease(userCouponId) == 1;
    }

    // ---------- L2 过期流转（2026-09-12） ----------

    @Override
    public boolean casExpireUsable(long userCouponId) {
        return mapper.casExpireUsable(userCouponId) == 1;
    }

    @Override
    public boolean casExpireOnLock(long userCouponId) {
        return mapper.casExpireOnLock(userCouponId) == 1;
    }

    @Override
    public java.util.List<Long> selectExpiredUsableIds(int limit) {
        return mapper.selectExpiredUsableIds(limit);
    }

    @Override
    public boolean casTimeoutClose(long userCouponId) {
        return mapper.casTimeoutClose(userCouponId) == 1;
    }
}
