package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponOutboxPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 发件箱 Mapper（ADR-0011）。原则同 TemplateMapper：涉及投递状态迁移的写操作
 * 必须手写带条件的 CAS SQL（投递器多实例并发时，谁抢到谁投）。
 */
@Mapper
public interface CouponOutboxMapper extends BaseMapper<CouponOutboxPO> {

    /** 扫待投递（投递器轮询入口）：status=0 且退避时间已到，按序限量 */
    @Select("SELECT * FROM coupon_outbox WHERE status = 0 AND next_scan_time <= #{now} "
            + "ORDER BY id LIMIT #{limit}")
    List<CouponOutboxPO> selectDispatchable(@Param("now") LocalDateTime now,
                                            @Param("limit") int limit);

    /** 投递成功：CAS 置已投递（带 status=0 条件——多实例/重复扫并发安全） */
    @Update("UPDATE coupon_outbox SET status = 1 WHERE id = #{id} AND status = 0")
    int markDispatched(@Param("id") long id);

    /** 投递失败：退避重试（2^retry 秒）——幂等重入，每次失败 retry+1、顺延下次 */
    @Update("UPDATE coupon_outbox SET retry_count = retry_count + 1, "
            + "next_scan_time = #{nextScanTime}, last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 0")
    int markRetry(@Param("id") long id,
                  @Param("nextScanTime") LocalDateTime nextScanTime,
                  @Param("lastError") String lastError);

    /** 重试耗尽 → 死信（人工介入；消息不丢——行还在） */
    @Update("UPDATE coupon_outbox SET status = 2, last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 0")
    int markDead(@Param("id") long id, @Param("lastError") String lastError);

    /** 守恒断言（T+1 日审）：发放类事件行数 ≡ 发件箱行数——同事务双写的直接推论 */
    @Select("SELECT (SELECT COUNT(*) FROM coupon_event WHERE event_type = 1) "
            + "- (SELECT COUNT(*) FROM coupon_outbox WHERE event_type = 1)")
    long issuedEventMinusOutbox();
}
