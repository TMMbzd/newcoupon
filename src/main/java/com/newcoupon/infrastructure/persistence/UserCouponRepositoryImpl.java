package com.newcoupon.infrastructure.persistence;

import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.converter.UserCouponPOConverter;
import com.newcoupon.infrastructure.persistence.mapper.UserCouponMapper;
import com.newcoupon.infrastructure.persistence.po.UserCouponPO;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 用户券仓储适配器 */
@Repository
public class UserCouponRepositoryImpl implements UserCouponRepository {

    private final UserCouponMapper mapper;

    public UserCouponRepositoryImpl(UserCouponMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserCoupon> findByIdempotentKey(long userId, String idempotentKey) {
        return Optional.ofNullable(mapper.selectByIdempotentKey(userId, idempotentKey))
                .map(UserCouponPOConverter::toDomain);
    }

    @Override
    public Optional<UserCoupon> findByUserAndTemplate(long userId, long templateId) {
        return Optional.ofNullable(mapper.selectByUserAndTemplate(userId, templateId))
                .map(UserCouponPOConverter::toDomain);
    }

    @Override
    public UserCoupon save(UserCoupon userCoupon) {
        UserCouponPO po = UserCouponPOConverter.toPO(userCoupon, userCoupon.getReceiveTime());
        mapper.insert(po);   // 撞 uk_idem / uk_user_template → DuplicateKeyException 抛给上层
        return userCoupon.withId(po.getId());
    }
}
