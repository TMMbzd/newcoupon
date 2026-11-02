package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 批量发放按用户明细（V39）：一行=一个用户一张目标券。
 *
 * <p>status 只允许 0→终态单向迁移，CAS（"AND status = 0"）是计数守恒的锚点：
 * 两个 worker 重放同一 item 时只有一个 CAS 赢家能迁移状态并累加计数。
 *
 * <p>receive 幂等键 = {@code grant-{taskNo}-{seq}}（确定性派生）：worker 重放
 * 已"发券成功但未记账"的 item 时，receive 返回原券（duplicated），记账一次不重发。
 */
@TableName("coupon_grant_item")
public class CouponGrantItemPO {

    /** 0 待执行 */
    public static final int STATUS_PENDING = 0;
    /** 1 发放成功（含幂等重放命中——返回原券，视为成功） */
    public static final int STATUS_SUCCESS = 1;
    /** 2 终态失败（售罄 3001/日限 3003/模板不可领 2001/2002/2003、重试耗尽） */
    public static final int STATUS_FAIL = 2;
    /** 3 未发放跳过（3002 已限领——用户多半已在别处拿到同模板券） */
    public static final int STATUS_SKIP = 3;
    /** 4 任务取消收尾（计数归入 skip） */
    public static final int STATUS_CANCELED = 4;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer chunkNo;
    private Long userId;
    private Integer seq;
    private Integer status;
    private Long userCouponId;
    private Integer failCode;
    private String failMsg;
    private Integer attempt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getChunkNo() { return chunkNo; }
    public void setChunkNo(Integer chunkNo) { this.chunkNo = chunkNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getUserCouponId() { return userCouponId; }
    public void setUserCouponId(Long userCouponId) { this.userCouponId = userCouponId; }
    public Integer getFailCode() { return failCode; }
    public void setFailCode(Integer failCode) { this.failCode = failCode; }
    public String getFailMsg() { return failMsg; }
    public void setFailMsg(String failMsg) { this.failMsg = failMsg; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
