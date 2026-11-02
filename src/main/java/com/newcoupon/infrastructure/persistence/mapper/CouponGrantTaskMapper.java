package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponGrantTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批量发放任务 Mapper（V39）。计数更新全部是"原子自增 + 条件迁移"：
 * 不读-改-写整行，避免两个 worker 并发累加时互相覆盖（丢计数=破坏守恒）。
 */
@Mapper
public interface CouponGrantTaskMapper extends BaseMapper<CouponGrantTaskPO> {

    /** 按任务号精确定位（对外暴露 taskNo 而非自增 id）。 */
    @Select("SELECT * FROM coupon_grant_task WHERE task_no = #{taskNo}")
    CouponGrantTaskPO selectByTaskNo(@Param("taskNo") String taskNo);

    /** 受理幂等：按租户 + requestId 定位既有任务（uk 终审）。 */
    @Select("SELECT * FROM coupon_grant_task WHERE app_id = #{appId} AND request_id = #{requestId}")
    CouponGrantTaskPO selectByAppAndRequest(@Param("appId") String appId,
                                            @Param("requestId") String requestId);

    /** worker 每 item 执行前的轻量复查：任务是否已被取消（避免给取消任务继续发券）。 */
    @Select("SELECT status FROM coupon_grant_task WHERE id = #{id}")
    Integer selectStatus(@Param("id") long id);

    /** 首个 chunk 被领取时 PENDING → RUNNING（幂等：只有 0→1 一次生效）。 */
    @Update("UPDATE coupon_grant_task SET status = 1, version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 0")
    int markRunning(@Param("id") long id);

    /** 取消受理：只允许 PENDING/RUNNING → CANCELING；终态任务拒绝（CAS）。 */
    @Update("UPDATE coupon_grant_task SET status = 4, version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status IN (0, 1)")
    int markCanceling(@Param("id") long id);

    /** 收尾①：全部 chunk 终态且任务处于 CANCELING → CANCELED。 */
    @Update("UPDATE coupon_grant_task SET status = 5, version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 4")
    int finalizeCanceled(@Param("id") long id);

    /** 收尾②：全部 chunk 终态且未被取消 → SUCCESS（fail&gt;0 仍是 SUCCESS，明细可查）。 */
    @Update("UPDATE coupon_grant_task SET status = 2, version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status IN (0, 1)")
    int finalizeSuccess(@Param("id") long id);

    /** item 成功记账：success+1、pending-1（与 item CAS 同事务调用）。 */
    @Update("UPDATE coupon_grant_task SET success_count = success_count + 1, "
            + "pending_count = pending_count - 1, update_time = NOW(3) WHERE id = #{id}")
    int addSuccess(@Param("id") long id);

    /** item 终态失败记账：fail+1、pending-1。 */
    @Update("UPDATE coupon_grant_task SET fail_count = fail_count + 1, "
            + "pending_count = pending_count - 1, update_time = NOW(3) WHERE id = #{id}")
    int addFail(@Param("id") long id);

    /** item 跳过/取消记账（n 个一批）：skip+n、pending-n（取消收尾用同一入口）。 */
    @Update("UPDATE coupon_grant_task SET skip_count = skip_count + #{n}, "
            + "pending_count = pending_count - #{n}, update_time = NOW(3) WHERE id = #{id}")
    int addSkip(@Param("id") long id, @Param("n") int n);

    /**
     * retry-failed 记账：fail-n、pending+n，状态 SUCCESS/FAILED → RUNNING。
     * WHERE 限定终态（2/3）是并发闸门：两个 retry-failed 同击一个任务时只有一个
     * CAS 打得中，输家拿到 0 行回滚整笔（item 重置与计数迁移必须同进退，否则守恒破裂）。
     */
    @Update("UPDATE coupon_grant_task SET fail_count = fail_count - #{n}, "
            + "pending_count = pending_count + #{n}, status = 1, "
            + "version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status IN (2, 3)")
    int reopenFailed(@Param("id") long id, @Param("n") int n);

    /**
     * 收尾兜底扫描：任务还开着（PENDING/RUNNING/CANCELING）但已没有任何
     * 非终态 chunk——收尾竞态里双双让路的任务由它捞回（worker 每轮先扫一遍）。
     */
    @Select("SELECT t.id FROM coupon_grant_task t WHERE t.status IN (0, 1, 4) "
            + "AND NOT EXISTS (SELECT 1 FROM coupon_grant_chunk c "
            + "WHERE c.task_id = t.id AND c.status IN (0, 1)) LIMIT 50")
    List<Long> selectStuckTaskIds();
}
