package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 批量发放执行单元（V39）：同一库存分片（user_id % 模板分片数 相同）的 item
 * 聚成一个 chunk，worker 以 chunk 为单位领取（FOR UPDATE SKIP LOCKED + lease）。
 *
 * <p>租约到期的 RUNNING chunk 会被下一轮重新领取——安全性不靠租约，
 * 靠 item 状态 CAS 与 receive 幂等键（重放不重复发放、不重复计数）。
 */
@TableName("coupon_grant_chunk")
public class CouponGrantChunkPO {

    /** 0 待领取 */
    public static final int STATUS_PENDING = 0;
    /** 1 执行中（持有 lease；lease_until 过期后可被回收重领） */
    public static final int STATUS_RUNNING = 1;
    /** 2 完成（chunk 内无 PENDING item） */
    public static final int STATUS_DONE = 2;
    /** 3 终止（任务取消的收尾；chunk 没有执行完它的发放使命） */
    public static final int STATUS_FAILED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer chunkNo;
    private Integer shardNo;
    private Long totalCount;
    private Long successCount;
    private Long failCount;
    private Long skipCount;
    private Integer status;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Integer attempt;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getChunkNo() { return chunkNo; }
    public void setChunkNo(Integer chunkNo) { this.chunkNo = chunkNo; }
    public Integer getShardNo() { return shardNo; }
    public void setShardNo(Integer shardNo) { this.shardNo = shardNo; }
    public Long getTotalCount() { return totalCount; }
    public void setTotalCount(Long totalCount) { this.totalCount = totalCount; }
    public Long getSuccessCount() { return successCount; }
    public void setSuccessCount(Long successCount) { this.successCount = successCount; }
    public Long getFailCount() { return failCount; }
    public void setFailCount(Long failCount) { this.failCount = failCount; }
    public Long getSkipCount() { return skipCount; }
    public void setSkipCount(Long skipCount) { this.skipCount = skipCount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
