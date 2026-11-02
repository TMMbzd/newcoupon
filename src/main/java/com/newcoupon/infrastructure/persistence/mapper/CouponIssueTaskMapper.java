package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 异步发放任务 Mapper（V27，ADR-0019）。
 *
 * <p>本阶段只提供受理幂等所需的唯一键查询；worker 的
 * {@code FOR UPDATE SKIP LOCKED} 领取、lease 续约和终态更新在实施 worker 时再加，
 * 不提前把未使用的 SQL 写进来。
 */
@Mapper
public interface CouponIssueTaskMapper extends BaseMapper<CouponIssueTaskPO> {

    /** 受理/查询共用：按租户 + 用户 + requestId 精确定位任务。 */
    @Select("SELECT * FROM coupon_issue_task "
            + "WHERE app_id = #{appId} AND user_id = #{userId} AND request_id = #{requestId}")
    CouponIssueTaskPO selectByUniqueKey(@Param("appId") String appId,
                                        @Param("userId") long userId,
                                        @Param("requestId") String requestId);

    @Select("SELECT COUNT(*) FROM coupon_issue_task WHERE status = #{status}")
    long countByStatus(@Param("status") int status);

    /** reshard 软排空：只等 PROCESSING（status=1），不等 PENDING。 */
    @Select("SELECT COUNT(*) FROM coupon_issue_task "
            + "WHERE template_id = #{templateId} AND status = #{status}")
    long countByTemplateAndStatus(@Param("templateId") long templateId,
                                  @Param("status") int status);

    /**
     * 领取一批可执行任务：PENDING，或 PROCESSING 但租约已过期。
     * `FOR UPDATE SKIP LOCKED` 让多实例各领各的，不互相等待；行锁保持到 markProcessing 提交。
     */
    @Select("SELECT * FROM coupon_issue_task "
            + "WHERE status IN (0, 1) AND next_scan_time <= #{now} "
            + "AND (lease_until IS NULL OR lease_until < #{now}) "
            + "ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<CouponIssueTaskPO> selectClaimable(@Param("now") LocalDateTime now,
                                            @Param("limit") int limit);

    /** 领取后立刻打 lease；条件里再判一次，防已被其它 worker 抢走。 */
    @Update("<script>UPDATE coupon_issue_task SET status = 1, lease_owner = #{owner}, "
            + "lease_until = #{leaseUntil}, update_time = NOW(3) WHERE id IN "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND status IN (0, 1) AND (lease_until IS NULL OR lease_until &lt; #{now})</script>")
    int markProcessing(@Param("ids") List<Long> ids,
                       @Param("owner") String owner,
                       @Param("leaseUntil") LocalDateTime leaseUntil,
                       @Param("now") LocalDateTime now);

    /** 续租：处理中的任务批量延长 lease，owner 条件防止旧 worker 续到新 owner 的任务。 */
    @Update("<script>UPDATE coupon_issue_task SET lease_until = #{leaseUntil}, update_time = NOW(3) "
            + "WHERE id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND lease_owner = #{owner} AND status = 1</script>")
    int renewLease(@Param("ids") List<Long> ids, @Param("owner") String owner,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 成功：券与任务状态在同一事务提交；lease_owner 条件防旧 worker 覆盖。 */
    @Update("UPDATE coupon_issue_task SET status = 2, coupon_id = #{couponId}, "
            + "error_code = NULL, error_msg = NULL, lease_owner = NULL, lease_until = NULL, "
            + "gate_release_state = 1, gate_next_retry_time = NULL, update_time = NOW(3) "
            + "WHERE id = #{id} AND lease_owner = #{owner} AND status = 1")
    int markSuccess(@Param("id") long id, @Param("owner") String owner,
                    @Param("couponId") long couponId);

    /** 业务拒绝：库存/日限/限领等终态，不重试；交 gate 补偿任务按错误码释放。 */
    @Update("UPDATE coupon_issue_task SET status = 3, error_code = #{errorCode}, "
            + "error_msg = #{errorMsg}, lease_owner = NULL, lease_until = NULL, "
            + "gate_release_state = 0, gate_next_retry_time = NOW(3), update_time = NOW(3) "
            + "WHERE id = #{id} AND lease_owner = #{owner} AND status = 1")
    int markRejected(@Param("id") long id, @Param("owner") String owner,
                     @Param("errorCode") int errorCode, @Param("errorMsg") String errorMsg);

    /** 瞬时失败：退回 PENDING 并退避，等待下一次领取。 */
    @Update("UPDATE coupon_issue_task SET status = 0, retry_count = retry_count + 1, "
            + "next_scan_time = #{nextScanTime}, error_code = #{errorCode}, error_msg = #{errorMsg}, "
            + "lease_owner = NULL, lease_until = NULL, update_time = NOW(3) "
            + "WHERE id = #{id} AND lease_owner = #{owner} AND status = 1")
    int markRetry(@Param("id") long id, @Param("owner") String owner,
                  @Param("nextScanTime") LocalDateTime nextScanTime,
                  @Param("errorCode") int errorCode, @Param("errorMsg") String errorMsg);

    /** 重试耗尽：DEAD，等待人工/对账处理；gate 补偿任务先探券再决定是否释放。 */
    @Update("UPDATE coupon_issue_task SET status = 4, error_code = #{errorCode}, "
            + "error_msg = #{errorMsg}, lease_owner = NULL, lease_until = NULL, "
            + "gate_release_state = 0, gate_next_retry_time = NOW(3), update_time = NOW(3) "
            + "WHERE id = #{id} AND lease_owner = #{owner} AND status = 1")
    int markDead(@Param("id") long id, @Param("owner") String owner,
                 @Param("errorCode") int errorCode, @Param("errorMsg") String errorMsg);

    /**
     * 在途任务数 = **仍持有 gate 预留的受理**：PENDING/PROCESSING，加上已终态但补偿任务
     * 还没释放的（status 3/4 且 gate_release_state=0）。后一项是 2026-10-29 对账评审后补的：
     * 任务终态提交到补偿释放之间最长有 30 秒滞后，这段时间 Redis 计数仍含该预留；
     * 若只数 status 0/1，重建/对账目标会比 Redis 低 1，先"修掉"预留、随后补偿再 DECR，
     * 制造一次反向漂移。按"预留是否仍在"计数，等式两边在滞后窗口内也一致。
     */
    @Select("SELECT COUNT(*) FROM coupon_issue_task "
            + "WHERE template_id = #{templateId} "
            + "AND status IN (0, 1, 3, 4) AND gate_release_state = 0")
    long countInFlight(@Param("templateId") long templateId);

    /** 每人总限计数重建：DB 已发券 + 仍持有预留的受理（口径同上）。 */
    @Select("SELECT COUNT(*) FROM coupon_issue_task "
            + "WHERE user_id = #{userId} AND template_id = #{templateId} "
            + "AND (status IN (0, 1) OR (status IN (3, 4) AND gate_release_state = 0))")
    long countInFlightByUserAndTemplate(@Param("userId") long userId,
                                        @Param("templateId") long templateId);

    /** 每人日限计数重建：DB 当日已发 + 当日仍持有预留的受理（口径同上）。 */
    @Select("SELECT COUNT(*) FROM coupon_issue_task "
            + "WHERE user_id = #{userId} AND template_id = #{templateId} "
            + "AND stat_day = #{statDay} "
            + "AND (status IN (0, 1) OR (status IN (3, 4) AND gate_release_state = 0))")
    long countInFlightByUserTemplateDay(@Param("userId") long userId,
                                        @Param("templateId") long templateId,
                                        @Param("statDay") java.time.LocalDate statDay);

    /** 模板日限计数重建：DB 当日已发 + 当日仍持有预留的受理（口径同上）。 */
    @Select("SELECT COUNT(*) FROM coupon_issue_task "
            + "WHERE template_id = #{templateId} AND stat_day = #{statDay} "
            + "AND (status IN (0, 1) OR (status IN (3, 4) AND gate_release_state = 0))")
    long countInFlightByTemplateDay(@Param("templateId") long templateId,
                                    @Param("statDay") java.time.LocalDate statDay);

    /**
     * gate 补偿候选：终态且未释放/待重试，到点后按 id 顺序取。
     * 补偿任务本身用 RedisScanLock 单实例；这里仍加 SKIP LOCKED，便于未来多实例安全。
     */
    @Select("SELECT * FROM coupon_issue_task "
            + "WHERE status IN (3, 4) AND gate_release_state IN (0, 3) "
            + "AND (gate_next_retry_time IS NULL OR gate_next_retry_time <= #{now}) "
            + "ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<CouponIssueTaskPO> selectGateReleaseCandidates(@Param("now") LocalDateTime now,
                                                        @Param("limit") int limit);

    /** 标记 gate 已释放（幂等；重复调用影响 0 行）。 */
    @Update("UPDATE coupon_issue_task SET gate_release_state = 2, gate_next_retry_time = NULL, "
            + "update_time = NOW(3) WHERE id = #{id} AND gate_release_state IN (0, 3)")
    int markGateReleased(@Param("id") long id);

    /** 释放失败/暂不可判定：退避重试。 */
    @Update("UPDATE coupon_issue_task SET gate_release_state = 3, gate_next_retry_time = #{next}, "
            + "update_time = NOW(3) WHERE id = #{id} AND gate_release_state IN (0, 3)")
    int markGateRetry(@Param("id") long id, @Param("next") LocalDateTime next);

    /** DEAD 任务探到券：把任务修复为 SUCCESS，同时标记 gate 已签发（不释放）。 */
    @Update("UPDATE coupon_issue_task SET status = 2, coupon_id = #{couponId}, "
            + "error_code = NULL, error_msg = NULL, lease_owner = NULL, lease_until = NULL, "
            + "gate_release_state = 1, gate_next_retry_time = NULL, update_time = NOW(3) "
            + "WHERE id = #{id} AND status IN (3, 4)")
    int markRecoveredSuccess(@Param("id") long id, @Param("couponId") long couponId);

    /** 清理终态任务：只删 SUCCESS/REJECTED/DEAD，绝不删 PENDING/PROCESSING。 */
    @Delete("DELETE FROM coupon_issue_task WHERE status IN (2, 3, 4) "
            + "AND create_time < #{cutoff} ORDER BY id LIMIT #{limit}")
    int deleteTerminalBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
