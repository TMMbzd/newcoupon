package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponGrantItemPO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批量发放 item Mapper（V39）。所有状态迁移都带 "AND status = 0"（CAS）：
 * 返回值=1 才是迁移赢家，调用方只对赢家累加计数——这是守恒不变量的实现锚点。
 */
@Mapper
public interface CouponGrantItemMapper extends BaseMapper<CouponGrantItemPO> {

    /** 领取 chunk 后取待执行明细，按 seq 升序（与受理顺序一致，排障可对齐）。 */
    @Select("SELECT * FROM coupon_grant_item "
            + "WHERE task_id = #{taskId} AND chunk_no = #{chunkNo} AND status = 0 ORDER BY seq")
    List<CouponGrantItemPO> selectPendingByChunk(@Param("taskId") long taskId,
                                                 @Param("chunkNo") int chunkNo);

    /** chunk 收尾判据：还有多少 PENDING item。 */
    @Select("SELECT COUNT(*) FROM coupon_grant_item "
            + "WHERE task_id = #{taskId} AND chunk_no = #{chunkNo} AND status = 0")
    long countPendingByChunk(@Param("taskId") long taskId, @Param("chunkNo") int chunkNo);

    /** 成功：回填券 id（含幂等重放——receive 返回原券，同样记 SUCCESS 一次）。 */
    @Update("UPDATE coupon_grant_item SET status = 1, user_coupon_id = #{couponId}, "
            + "attempt = attempt + 1, update_time = NOW(3) WHERE id = #{id} AND status = 0")
    int markSuccess(@Param("id") long id, @Param("couponId") long couponId);

    /** 终态失败：记录业务码（3001/3003/2001/2002/2003、重试耗尽）。 */
    @Update("UPDATE coupon_grant_item SET status = 2, fail_code = #{failCode}, "
            + "fail_msg = #{failMsg}, attempt = attempt + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 0")
    int markFail(@Param("id") long id, @Param("failCode") int failCode,
                 @Param("failMsg") String failMsg);

    /** 跳过（3002 已限领：用户多半已在别处拿到同模板券，skip 语义=未发放）。 */
    @Update("UPDATE coupon_grant_item SET status = 3, fail_code = #{failCode}, "
            + "fail_msg = #{failMsg}, attempt = attempt + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 0")
    int markSkip(@Param("id") long id, @Param("failCode") int failCode,
                 @Param("failMsg") String failMsg);

    /** 可重试失败：保持 PENDING、attempt+1；退避写在 chunk 上（下轮重领整 chunk）。 */
    @Update("UPDATE coupon_grant_item SET attempt = attempt + 1, fail_code = #{failCode}, "
            + "fail_msg = #{failMsg}, update_time = NOW(3) WHERE id = #{id} AND status = 0")
    int markRetry(@Param("id") long id, @Param("failCode") int failCode,
                  @Param("failMsg") String failMsg);

    /** 取消收尾：把一个 chunk 内全部 PENDING item 置 CANCELED（返回迁移数量）。 */
    @Update("UPDATE coupon_grant_item SET status = 4, update_time = NOW(3) "
            + "WHERE task_id = #{taskId} AND chunk_no = #{chunkNo} AND status = 0")
    int cancelPendingByChunk(@Param("taskId") long taskId, @Param("chunkNo") int chunkNo);

    /** 任务级兜底清扫：收尾事务里把残留 PENDING（理论为 0）置 CANCELED，防悬挂。 */
    @Update("UPDATE coupon_grant_item SET status = 4, update_time = NOW(3) "
            + "WHERE task_id = #{taskId} AND status = 0")
    int cancelPendingByTask(@Param("taskId") long taskId);

    @Select("SELECT * FROM coupon_grant_item WHERE task_id = #{taskId} ORDER BY chunk_no, seq")
    List<CouponGrantItemPO> selectByTask(@Param("taskId") long taskId);

    /** 失败明细导出：取任务内全部终态 FAIL item，按 seq 升序（与受理顺序一致，导出行序稳定）。 */
    @Select("SELECT * FROM coupon_grant_item "
            + "WHERE task_id = #{taskId} AND status = 2 ORDER BY seq")
    List<CouponGrantItemPO> selectFailedByTask(@Param("taskId") long taskId);

    /**
     * FAIL item 的 chunk_no 列表（V6 阶段 1C）：retry-failed 补预算预占时按 chunk
     * 分组计数用。单查询拉回后 Java 侧分组，避免为 ≤1 万行的任务做 N 次点查。
     */
    @Select("SELECT chunk_no FROM coupon_grant_item WHERE task_id = #{taskId} AND status = 2")
    List<Integer> selectFailChunkNos(@Param("taskId") long taskId);

    /**
     * retry-failed 重置：把任务内全部 FAIL item 批量置回 PENDING，attempt 归零、
     * 失败原因清空。返回重置行数 n——n 是调用方迁移任务计数（fail-n、pending+n）的
     * 唯一依据；WHERE 限定 status=2 保证只重置终态失败项，SUCCESS/SKIP/CANCELED 不被动。
     */
    @Update("UPDATE coupon_grant_item SET status = 0, attempt = 0, "
            + "fail_code = NULL, fail_msg = NULL, update_time = NOW(3) "
            + "WHERE task_id = #{taskId} AND status = 2")
    int resetFailedToPending(@Param("taskId") long taskId);

    /** 建任务时批量插入明细（分片内切片批量，避免一条万行 VALUES 的巨型 SQL）。 */
    @Insert("<script>INSERT INTO coupon_grant_item "
            + "(task_id, chunk_no, user_id, seq, status, attempt, create_time, update_time) VALUES "
            + "<foreach collection='items' item='i' separator=','>"
            + "(#{i.taskId}, #{i.chunkNo}, #{i.userId}, #{i.seq}, 0, 0, NOW(3), NOW(3))"
            + "</foreach></script>")
    int insertBatch(@Param("items") List<CouponGrantItemPO> items);
}
