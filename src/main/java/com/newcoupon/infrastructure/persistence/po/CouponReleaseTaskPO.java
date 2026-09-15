package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 锁定超时任务 PO，对应表 coupon_release_task（V7，L5）——"哪份合同、该何时收场、处理到哪了"。
 * 与 CouponExpireTaskPO 同骨架（"同一骨架换谓词换动作"）；主体是**合同**（usage_no 唯一）
 * 而非券——一张券一生可签多份合同，按券唯一会挡第二次注册（评审修正，见 V7 注释）。
 * 状态语义：0 待处理（含重试）/ 1 已终结 / 3 死信（重试超限，人工处理）。
 */
@TableName("coupon_release_task")
public class CouponReleaseTaskPO {

    @TableId(type = IdType.INPUT)   // 雪花ID显式赋值（ADR-0010批次二）
    private Long id;
    private String usageNo;
    private Long couponId;
    private Long userId;
    private Integer shard;
    private LocalDateTime nextScanTime;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsageNo() { return usageNo; }
    public void setUsageNo(String usageNo) { this.usageNo = usageNo; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Integer getShard() { return shard; }
    public void setShard(Integer shard) { this.shard = shard; }
    public LocalDateTime getNextScanTime() { return nextScanTime; }
    public void setNextScanTime(LocalDateTime nextScanTime) { this.nextScanTime = nextScanTime; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
