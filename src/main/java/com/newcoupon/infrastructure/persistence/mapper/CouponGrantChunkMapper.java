package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponGrantChunkPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批量发放 chunk Mapper（V39）。领取 SQL 照 {@code CouponIssueTaskMapper.selectClaimable}
 * 的模型：`FOR UPDATE SKIP LOCKED` 让多实例各领各的，行锁只保持到领取事务提交。
 */
@Mapper
public interface CouponGrantChunkMapper extends BaseMapper<CouponGrantChunkPO> {

    /**
     * 领取一条到期 chunk：PENDING，或 RUNNING 但租约已过期（worker 崩溃回收）；
     * 且退避时间已到（可重试 item 留下的 next_retry_at）。
     * LIMIT 1：本刀单 worker 串行逐 chunk 执行（max_concurrency=1）。
     */
    @Select("SELECT * FROM coupon_grant_chunk "
            + "WHERE (status = 0 OR (status = 1 AND lease_until < #{now})) "
            + "AND (next_retry_at IS NULL OR next_retry_at <= #{now}) "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
    CouponGrantChunkPO selectClaimable(@Param("now") LocalDateTime now);

    /** 领取后打 lease：条件里再判一次领取资格，防其它 worker 抢先。 */
    @Update("UPDATE coupon_grant_chunk SET status = 1, lease_owner = #{owner}, "
            + "lease_until = #{leaseUntil}, update_time = NOW(3) "
            + "WHERE id = #{id} AND (status = 0 OR (status = 1 AND lease_until < #{now}))")
    int markRunning(@Param("id") long id,
                    @Param("owner") String owner,
                    @Param("leaseUntil") LocalDateTime leaseUntil,
                    @Param("now") LocalDateTime now);

    /** item 成功记账：chunk success+1（与 item CAS 同事务）。 */
    @Update("UPDATE coupon_grant_chunk SET success_count = success_count + 1, "
            + "update_time = NOW(3) WHERE id = #{id}")
    int addSuccess(@Param("id") long id);

    /** item 终态失败记账。 */
    @Update("UPDATE coupon_grant_chunk SET fail_count = fail_count + 1, "
            + "update_time = NOW(3) WHERE id = #{id}")
    int addFail(@Param("id") long id);

    /** item 跳过/取消记账（n 个一批，取消收尾用）。 */
    @Update("UPDATE coupon_grant_chunk SET skip_count = skip_count + #{n}, "
            + "update_time = NOW(3) WHERE id = #{id}")
    int addSkip(@Param("id") long id, @Param("n") int n);

    /** chunk 完成：无 PENDING item 时 CAS 到 DONE；owner 条件防失去租约的旧 worker 覆盖。 */
    @Update("UPDATE coupon_grant_chunk SET status = 2, lease_owner = NULL, lease_until = NULL, "
            + "update_time = NOW(3) WHERE id = #{id} AND status = 1 AND lease_owner = #{owner}")
    int markDone(@Param("id") long id, @Param("owner") String owner);

    /** 退避：仍有 PENDING item（可重试类）时保持 RUNNING、释放租约、下次到点再领。 */
    @Update("UPDATE coupon_grant_chunk SET lease_owner = NULL, lease_until = #{until}, "
            + "next_retry_at = #{until}, attempt = attempt + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 1 AND lease_owner = #{owner}")
    int backoff(@Param("id") long id, @Param("owner") String owner,
                @Param("until") LocalDateTime until);

    /** 释放租约但可立即重领（时间盒耗尽/执行异常）：lease 置为过去，保持 RUNNING。 */
    @Update("UPDATE coupon_grant_chunk SET lease_owner = NULL, lease_until = #{until}, "
            + "update_time = NOW(3) WHERE id = #{id} AND status = 1 AND lease_owner = #{owner}")
    int releaseLease(@Param("id") long id, @Param("owner") String owner,
                     @Param("until") LocalDateTime until);

    /** 取消收尾：chunk 置 FAILED 并清租约；owner 条件保证只有租约持有人或未领取的 chunk 可被收。 */
    @Update("UPDATE coupon_grant_chunk SET status = 3, lease_owner = NULL, lease_until = NULL, "
            + "update_time = NOW(3) "
            + "WHERE id = #{id} AND status IN (0, 1) AND (lease_owner IS NULL OR lease_owner = #{owner})")
    int markCanceled(@Param("id") long id, @Param("owner") String owner);

    /** 任务收尾判据：还有多少非终态 chunk（0 = 可以收尾任务）。 */
    @Select("SELECT COUNT(*) FROM coupon_grant_chunk WHERE task_id = #{taskId} AND status IN (0, 1)")
    long countNonTerminalByTask(@Param("taskId") long taskId);

    /**
     * retry-failed 重开 chunk：任务内已 DONE 且**现在又有 PENDING item** 的 chunk
     * 置回 PENDING、清掉租约、next_retry_at=当前时刻（下一轮立即可领）。EXISTS
     * 子查询按 (task_id, chunk_no) 关联刚被重置的 item——全部成功的 chunk 不含
     * PENDING item，不会被无谓重开（重开它只是空跑一轮领取+收尾）。
     */
    @Update("UPDATE coupon_grant_chunk SET status = 0, lease_owner = NULL, lease_until = NULL, "
            + "next_retry_at = #{now}, update_time = NOW(3) "
            + "WHERE task_id = #{taskId} AND status = 2 "
            + "AND EXISTS (SELECT 1 FROM coupon_grant_item "
            + "WHERE task_id = coupon_grant_chunk.task_id "
            + "AND chunk_no = coupon_grant_chunk.chunk_no AND status = 0)")
    int reopenDoneWithPendingItems(@Param("taskId") long taskId, @Param("now") java.time.LocalDateTime now);

    @Select("SELECT * FROM coupon_grant_chunk WHERE task_id = #{taskId} AND chunk_no = #{chunkNo}")
    CouponGrantChunkPO selectByTaskAndChunk(@Param("taskId") long taskId,
                                            @Param("chunkNo") int chunkNo);

    @Select("SELECT * FROM coupon_grant_chunk WHERE task_id = #{taskId} ORDER BY chunk_no")
    List<CouponGrantChunkPO> selectByTask(@Param("taskId") long taskId);

    /** 建任务时批量插入 chunk（单事务建任务的组成部分）。 */
    @Insert("<script>INSERT INTO coupon_grant_chunk "
            + "(task_id, chunk_no, shard_no, total_count, status, attempt, create_time, update_time) VALUES "
            + "<foreach collection='chunks' item='c' separator=','>"
            + "(#{c.taskId}, #{c.chunkNo}, #{c.shardNo}, #{c.totalCount}, 0, 0, NOW(3), NOW(3))"
            + "</foreach></script>")
    int insertBatch(@Param("chunks") List<CouponGrantChunkPO> chunks);
}
