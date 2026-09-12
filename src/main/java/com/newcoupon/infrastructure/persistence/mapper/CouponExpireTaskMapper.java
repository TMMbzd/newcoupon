package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponExpireTaskPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 过期任务 Mapper（V5，L2）。扫描谓词只认 status=0 一种值（等值命中 idx_scan 前缀）；
 * 失败不设"失败"状态，只推进 next_scan_time——"晚一点再扫"不会成为漏扫死角。
 */
@Mapper
public interface CouponExpireTaskMapper extends BaseMapper<CouponExpireTaskPO> {

    /**
     * 捞到期待办（分片版，走 idx_scan(status, next_scan_time)）：
     * XXL-JOB 分片广播时每个实例只捞自己的分片（shard % total = index），
     * 吞吐随实例数线性扩展；单通道（shardIndex=0, shardTotal=1）=全量。
     */
    @Select("SELECT * FROM coupon_expire_task WHERE status = 0 AND next_scan_time <= NOW() "
            + "AND shard % #{shardTotal} = #{shardIndex} "
            + "ORDER BY next_scan_time LIMIT #{limit}")
    List<CouponExpireTaskPO> scanDueShard(@Param("shardIndex") int shardIndex,
                                          @Param("shardTotal") int shardTotal,
                                          @Param("limit") int limit);

    /** 终结任务（幂等：已终结再执行影响 0 行） */
    @Update("UPDATE coupon_expire_task SET status = 1 WHERE coupon_id = #{couponId} AND status = 0")
    int finish(@Param("couponId") long couponId);

    /**
     * 失败退避：retry_count+1、下次扫描时间按指数退避推进（min(2^retry, 30min)）；
     * 重试满 8 次置死信(3)。SET 子句按 MySQL 左到右求值，status 的 IF 用的是自增后的 retry_count。
     */
    @Update("UPDATE coupon_expire_task SET "
            + "retry_count = retry_count + 1, "
            + "next_scan_time = NOW() + INTERVAL LEAST(POW(2, retry_count + 1), 1800) SECOND, "
            + "status = IF(retry_count + 1 >= 8, 3, 0) "
            + "WHERE coupon_id = #{couponId} AND status = 0")
    int backoff(@Param("couponId") long couponId);

    /** 审计补注册（幂等）：漏注册的券补任务，已存在则忽略 */
    @Update("INSERT IGNORE INTO coupon_expire_task (coupon_id, user_id, shard, next_scan_time, status, retry_count) "
            + "VALUES (#{couponId}, #{userId}, #{shard}, #{nextScanTime}, 0, 0)")
    int registerIfAbsent(@Param("couponId") long couponId, @Param("userId") long userId,
                         @Param("shard") int shard, @Param("nextScanTime") String nextScanTime);
}
