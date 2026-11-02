package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueGateRepairPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Redis gate 修复队列 Mapper（V36）。
 *
 * <p>领取用 {@code FOR UPDATE SKIP LOCKED} + lease：多实例各领各的，进程崩溃后租约过期可回收。
 * 所有状态更新都带 {@code lease_owner} 条件，旧实例覆盖不了新实例。
 */
@Mapper
public interface CouponIssueGateRepairMapper extends BaseMapper<CouponIssueGateRepairPO> {

    /** 同一作用域只保留一行；重复登记只把下次重试时间提前，不重复堆行。 */
    @Insert("INSERT INTO coupon_issue_gate_repair "
            + "(scope_type, template_id, user_id, stat_day, error_code, status, attempts, "
            + " next_retry_time, create_time, update_time) "
            + "VALUES (#{scopeType}, #{templateId}, #{userId}, #{statDay}, #{errorCode}, 0, 0, "
            + " #{nextRetryTime}, NOW(3), NOW(3)) "
            + "ON DUPLICATE KEY UPDATE "
            + " attempts = IF(status = 3, 0, attempts), "
            + " status = IF(status = 3, 0, status), "
            + " next_retry_time = LEAST(next_retry_time, VALUES(next_retry_time)), "
            + " update_time = NOW(3)")
    int upsert(CouponIssueGateRepairPO po);

    /** 到期可领：待处理/死信重试/处理中但租约过期。 */
    @Select("SELECT * FROM coupon_issue_gate_repair "
            + "WHERE next_retry_time <= #{now} "
            + "AND (status IN (0, 3) OR (status = 1 AND lease_until < #{now})) "
            + "ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<CouponIssueGateRepairPO> selectDue(@Param("now") LocalDateTime now,
                                            @Param("limit") int limit);

    @Update("<script>UPDATE coupon_issue_gate_repair "
            + "SET status = 1, lease_owner = #{owner}, lease_until = #{leaseUntil}, "
            + " update_time = NOW(3) WHERE id IN "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND (status IN (0, 3) OR (status = 1 AND lease_until &lt; #{now}))</script>")
    int markProcessing(@Param("ids") List<Long> ids,
                       @Param("owner") String owner,
                       @Param("leaseUntil") LocalDateTime leaseUntil,
                       @Param("now") LocalDateTime now);

    @Update("UPDATE coupon_issue_gate_repair SET status = 2, lease_owner = NULL, "
            + "lease_until = NULL, last_error = NULL, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 1 AND lease_owner = #{owner}")
    int markDone(@Param("id") long id, @Param("owner") String owner);

    @Update("UPDATE coupon_issue_gate_repair SET status = 0, attempts = attempts + 1, "
            + "next_retry_time = #{next}, lease_owner = NULL, lease_until = NULL, "
            + "last_error = #{lastError}, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 1 AND lease_owner = #{owner}")
    int markRetry(@Param("id") long id,
                  @Param("owner") String owner,
                  @Param("next") LocalDateTime next,
                  @Param("lastError") String lastError);

    @Update("UPDATE coupon_issue_gate_repair SET status = 3, attempts = attempts + 1, "
            + "lease_owner = NULL, lease_until = NULL, last_error = #{lastError}, "
            + "update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 1 AND lease_owner = #{owner}")
    int markDead(@Param("id") long id,
                 @Param("owner") String owner,
                 @Param("lastError") String lastError);

    @Select("SELECT COUNT(*) FROM coupon_issue_gate_repair WHERE status IN (0, 1, 3)")
    long countOpen();

    @Delete("DELETE FROM coupon_issue_gate_repair WHERE status = 2 "
            + "AND update_time < #{cutoff} ORDER BY id LIMIT #{limit}")
    int deleteDoneBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
