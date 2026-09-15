package com.newcoupon.domain.repository;

import com.newcoupon.domain.coupon.CouponUsage;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 占用单仓储端口。
 * 写操作全部是 CAS（条件更新）语义——状态迁移的正确性由数据库层保证，
 * 内存校验只负责报错质量（study/04a 3.1 讲过的"快检管体验，CAS 管正确"）。
 */
public interface CouponUsageRepository {

    /** 按占用单号查询（uk_usage_no 覆盖）：幂等判断与回退裁判的第一步 */
    Optional<CouponUsage> findByUsageNo(String usageNo);

    /** 新建占用单（初始=占用中）；撞 uk_usage_no 抛 DuplicateKeyException */
    CouponUsage save(CouponUsage usage);

    /** 占用单核销（CAS）：占用中→已核销，同时写入实际抵扣金额与核销时间。返回是否成功 */
    boolean casConfirm(String usageNo, long discountAmt, LocalDateTime useTime);

    /** 占用单释放（CAS）：占用中→已释放。返回是否成功 */
    boolean casRelease(String usageNo, LocalDateTime releaseTime);

    /** L2 过期关闭（CAS）：占用中→过期关闭（第三种终结：非核销非释放）。按占用单号 */
    boolean casExpireByUsageNo(String usageNo);

    /** L2 过期关闭（CAS）：按券id——扫描器路径（券锁定中过期，占用单同步收尾） */
    boolean casExpireByCouponId(long userCouponId);

    // ---------- L5 锁定超时收场（2026-09-15） ----------

    /** 超时关闭（CAS）：占用中→超时关闭（第四种终结：订单沉默，系统收场）。按占用单号 */
    boolean casTimeoutByUsageNo(String usageNo);

    /** T+1 锁龄审计：仍占用中且锁龄早于阈值的合同（漏注册任务兜底，走 idx_occupy） */
    java.util.List<CouponUsage> selectOverdueOccupying(LocalDateTime threshold, int limit);

    /** L4 交叉核对：全部占用中的合同（在途占用=小集合） */
    java.util.List<CouponUsage> selectOccupying(int limit);

    /** L4 点查复核：某券当前是否仍有占用中合同（diff 候选二次确认） */
    Optional<CouponUsage> findOccupyingByCouponId(long couponId);
}
