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
}
