package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 过期任务 PO，对应表 coupon_expire_task（V5，L2）——"哪张券、该何时处理、处理到哪了"的持久化待办。
 * 状态语义：0 待处理（含重试）/ 1 已终结 / 3 死信（重试超限，人工处理）。
 */
@TableName("coupon_expire_task")
public class CouponExpireTaskPO {

    @TableId(type = IdType.AUTO)
    private Long id;
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
