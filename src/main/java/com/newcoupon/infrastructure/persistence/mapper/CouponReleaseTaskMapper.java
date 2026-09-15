package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponReleaseTaskPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 锁定超时任务 Mapper（V7，L5）。与 CouponExpireTaskMapper 同骨架：
 * 扫描谓词只认 status=0 单值（等值命中 idx_scan 前缀）；失败不设"失败"状态，
 * 只推进 next_scan_time——"晚一点再扫"不会成为漏扫死角。
 */
@Mapper
public interface CouponReleaseTaskMapper extends BaseMapper<CouponReleaseTaskPO> {

    /** 捞到期待办（分片版，走 idx_scan(status, next_scan_time)；XXL-JOB 分片广播时各实例只捞自己的分片） */
    @Select("SELECT * FROM coupon_release_task WHERE status = 0 AND next_scan_time <= NOW() "
            + "AND shard % #{shardTotal} = #{shardIndex} "
            + "ORDER BY next_scan_time LIMIT #{limit}")
    List<CouponReleaseTaskPO> scanDueShard(@Param("shardIndex") int shardIndex,
                                           @Param("shardTotal") int shardTotal,
                                           @Param("limit") int limit);

    /** 终结任务（幂等：已终结再执行影响 0 行）——按合同号 */
    @Update("UPDATE coupon_release_task SET status = 1 WHERE usage_no = #{usageNo} AND status = 0")
    int finish(@Param("usageNo") String usageNo);

    /** 失败退避：retry_count+1、指数退避推进（min(2^retry, 30min)）；总失败满 8 次置死信(3)。
     *  注意 MySQL SET 从左到右求值：第 3 行的 retry_count 已是自增后的新值（=总失败次数），
     *  条件写 >=8 即"第 8 次失败死信"（红队评审#3：曾误写 retry_count+1>=8 实为第 7 次就死）。 */
    @Update("UPDATE coupon_release_task SET "
            + "retry_count = retry_count + 1, "
            + "next_scan_time = NOW() + INTERVAL LEAST(POW(2, retry_count + 1), 1800) SECOND, "
            + "status = IF(retry_count >= 8, 3, 0) "
            + "WHERE usage_no = #{usageNo} AND status = 0")
    int backoff(@Param("usageNo") String usageNo);

    /** 审计补注册：不存在则插入；**死信(3)则复活**为待处理并清零重试（红队评审#1：INSERT IGNORE
     *  会被死信行的唯一键静默吞掉——"补注册"对死信合同永远做不到，且告警谎报已自愈）。
     *  SET 求值顺序：retry_count 先判（此时 status 还是旧值 3），再改 status。 */
    @Update("INSERT INTO coupon_release_task (id, usage_no, coupon_id, user_id, shard, next_scan_time, status, retry_count) "
            + "VALUES (#{id}, #{usageNo}, #{couponId}, #{userId}, #{shard}, #{nextScanTime}, 0, 0) "
            + "ON DUPLICATE KEY UPDATE "
            + "retry_count = IF(status = 3, 0, retry_count), "
            + "status = IF(status = 3, 0, status)")
    int registerIfAbsent(@Param("id") long id, @Param("usageNo") String usageNo,
                         @Param("couponId") long couponId, @Param("userId") long userId,
                         @Param("shard") int shard, @Param("nextScanTime") String nextScanTime);
}
