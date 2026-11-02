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

    /** 按幂等键查询（唯一索引 uk_idem 覆盖）：幂等快查与 DuplicateKey 冲突分类用。
     *  appId 是多租户隔离维度；同一 user+requestId 在不同租户下是不同请求。 */
    Optional<UserCoupon> findByIdempotentKey(long userId, String appId, String idempotentKey);

    /** 按模板数券（ADR-0008 第二不变式）：对账修复前的守卫互证 issued_count ↔ COUNT(user_coupon) */
    long countByTemplate(long templateId);

    /**
     * L3 计数自愈/对账：用户对模板的实际领取行数。
     *
     * <p><b>不在领取热路径</b>：热路径每人总限用 {@link #maxSeqByUserAndTemplate}。
     * 本方法会对 user_coupon 做 COUNT，只用于低频自愈、对账和测试。
     */
    long countByUserAndTemplate(long userId, long templateId);

    /**
     * L42① 当日已领行数：日限计数虚高时复核用。
     *
     * <p><b>不在领取热路径</b>：热路径用 V27 {@code coupon_user_daily_counter} 条件自增。
     */
    long countTodayByUserAndTemplate(long userId, long templateId, java.time.LocalDateTime dayStart);

    /**
     * 领取热路径的每人总限来源：最大 seq。
     *
     * <p>seq 由 {@code uk_user_seq} 保证唯一；正常路径用 max(seq)+1 分配下一个序号。
     * 最大序号加一超过每人限领时不直接判达限——异步预分配的 seq 可能因任务拒绝留下空洞；
     * 调用方走冷路径 {@link #findSeqsByUserAndTemplate} 找最小空闲序号。
     */
    long maxSeqByUserAndTemplate(long userId, long templateId);

    /**
     * 冷路径：用户在该模板上已占用的 seq（升序）。
     *
     * <p>只在 max(seq)+1 超过限领时调用，用来区分“真的领满”和“序号空洞”；
     * 不进入正常领取热路径。列表最多 {@code perUserLimit} 个元素（上限 99）。
     */
    List<Integer> findSeqsByUserAndTemplate(long userId, long templateId);

    /** L4 交叉核对：全部锁定中的券（在途锁定=小集合） */
    List<UserCoupon> selectLocked(int limit);

    /** 按主键查询：用券三段与读回断言用（无 user_id→64 表广播，冷路径用） */
    Optional<UserCoupon> findById(long id);

    /** 热路径路由版主键查询（A1）：带分片键单表命中——锁券入口专用 */
    Optional<UserCoupon> findByIdForUser(long id, long userId);

    /** 查用户的券列表（status 为 null 查全部）："我的券包"接口用 */
    /**
     * 券包列表。
     *
     * @param appId 归属应用；{@code null} = 不过滤（内部面/运营视角）。
     *              开放面必须传调用方 appId —— 这是"用户 A 的券"与"应用 X 的券"
     *              两重作用域的**物理落实点**：不同应用传相同 userId 是不同的人。
     */
    List<UserCoupon> findByUser(long userId, CouponStatus status, String appId);

    /** 新建用户券，回填主键 */
    UserCoupon save(UserCoupon userCoupon);

    // ---------- 状态迁移 CAS（每条对应状态机一条合法边） ----------

    /** 锁券（CAS）：待使用→已锁定，条件含"未过期"。返回是否成功 */
    boolean casLock(long userCouponId, long userId);

    /** 核销（CAS）：已锁定→已核销（终态）。返回是否成功 */
    boolean casConfirm(long userCouponId, long userId);

    /** 整单退款退券（CAS）：已核销→(未过期?待使用:已过期)——部分退款不走这里 */
    boolean casReturnOnRefund(long userCouponId, long userId);

    /**
     * 释放（CAS）：已锁定→(未过期?待使用:已过期)——释放时券若已过期直接进过期态，
     * 不能退回"待使用"（否则出现"能用的过期券"）。返回是否成功
     */
    boolean casRelease(long userCouponId, long userId);

    // ---------- L2 过期流转（2026-09-12） ----------

    /** 到期未使用（CAS）：待使用→已过期（终态）。重复触发影响 0 行 */
    boolean casExpireUsable(long userCouponId, long userId);

    /** 锁定中过期（CAS）：已锁定→已过期（终态），守卫"确已到期" */
    boolean casExpireOnLock(long userCouponId, long userId);

    /** T+1 审计：仍待使用但过期超 10 分钟的券 id（漏注册任务兜底，走 idx_expire） */
    java.util.List<Long> selectExpiredUsableIds(int limit);

    /** L5 锁定超时收场（CAS）：已锁定→(未过期?待使用:已过期)——订单沉默时系统兜底，IF 分流同释放 */
    boolean casTimeoutClose(long userCouponId, long userId);
}
