package com.newcoupon.domain.repository;

import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import java.util.List;
import java.util.Optional;

/**
 * 用户券仓储端口。方法 = 编排层真实需要的查询场景，不做通用 CRUD 泄漏。
 * 写操作全部 CAS 语义（条件 UPDATE），配合 CouponStateMachine 的内存快检双层防并发。
 */
public interface UserCouponRepository {

    /** 按幂等键查询（唯一索引 uk_idem 覆盖）：幂等快查与 DuplicateKey 冲突分类用 */
    Optional<UserCoupon> findByIdempotentKey(long userId, String idempotentKey);

    /** 按模板数券（ADR-0008 第二不变式）：对账修复前的守卫互证 issued_count ↔ COUNT(user_coupon) */
    long countByTemplate(long templateId);

    /** 按主键查询：用券三段与读回断言用 */
    Optional<UserCoupon> findById(long id);

    /** 查用户的券列表（status 为 null 查全部）："我的券包"接口用 */
    List<UserCoupon> findByUser(long userId, CouponStatus status);

    /** 新建用户券，回填主键 */
    UserCoupon save(UserCoupon userCoupon);

    // ---------- 状态迁移 CAS（每条对应状态机一条合法边） ----------

    /** 锁券（CAS）：待使用→已锁定，条件含"未过期"。返回是否成功 */
    boolean casLock(long userCouponId);

    /** 核销（CAS）：已锁定→已核销（终态）。返回是否成功 */
    boolean casConfirm(long userCouponId);

    /**
     * 释放（CAS）：已锁定→(未过期?待使用:已过期)——释放时券若已过期直接进过期态，
     * 不能退回"待使用"（否则出现"能用的过期券"）。返回是否成功
     */
    boolean casRelease(long userCouponId);

    // ---------- L2 过期流转（2026-09-12） ----------

    /** 到期未使用（CAS）：待使用→已过期（终态）。重复触发影响 0 行 */
    boolean casExpireUsable(long userCouponId);

    /** 锁定中过期（CAS）：已锁定→已过期（终态），守卫"确已到期" */
    boolean casExpireOnLock(long userCouponId);

    /** T+1 审计：仍待使用但过期超 10 分钟的券 id（漏注册任务兜底，走 idx_expire） */
    java.util.List<Long> selectExpiredUsableIds(int limit);
}
