package com.newcoupon.domain.repository;

import com.newcoupon.domain.coupon.UserCoupon;
import java.util.Optional;

/**
 * 用户券仓储端口。方法 = 编排层真实需要的查询场景，不做通用 CRUD 泄漏：
 * 两个查询方法分别服务于幂等快查/冲突分类（ADR-0004 增补3）。
 */
public interface UserCouponRepository {

    /** 按幂等键查询（唯一索引 uk_idem 覆盖）：幂等快查与 DuplicateKey 冲突分类用 */
    Optional<UserCoupon> findByIdempotentKey(long userId, String idempotentKey);

    /** 按用户+模板查询（唯一索引 uk_user_template 覆盖）：超领冲突分类用 */
    Optional<UserCoupon> findByUserAndTemplate(long userId, long templateId);

    /** 新建用户券，回填主键 */
    UserCoupon save(UserCoupon userCoupon);
}
