package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 预算预占凭证 PO（V42，V6 阶段 1C）。一次业务意图一行：
 * 批量任务一个 chunk 一行（biz=taskNo-chunkNo，retry-failed 重开可追加多行）、
 * 同步单人领取/兑换码一次请求一行（biz=requestId）。
 * 状态机单向：0 预留 → 1 确认 / 2 释放 / 3 过期回收（后三者都是终态）。
 */
@TableName("coupon_budget_reservation")
public class CouponBudgetReservationPO {

    public static final int STATUS_RESERVED = 0;
    public static final int STATUS_CONFIRMED = 1;
    public static final int STATUS_RELEASED = 2;
    public static final int STATUS_EXPIRED = 3;

    /** 业务来源：1=批量 chunk 预占 */
    public static final int BIZ_CHUNK = 1;
    /** 业务来源：2=同步单人领取预占 */
    public static final int BIZ_SINGLE = 2;
    /** 业务来源：3=兑换码兑换预占 */
    public static final int BIZ_CODE = 3;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reserveNo;       // BR+雪花ID
    private Long accountId;
    private Integer bizType;
    private String bizId;
    private Long amount;            // 预占额（分）
    private Integer status;
    private Long confirmedAmount;   // 已确认额（分，累进）
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReserveNo() { return reserveNo; }
    public void setReserveNo(String reserveNo) { this.reserveNo = reserveNo; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public Integer getBizType() { return bizType; }
    public void setBizType(Integer bizType) { this.bizType = bizType; }
    public String getBizId() { return bizId; }
    public void setBizId(String bizId) { this.bizId = bizId; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getConfirmedAmount() { return confirmedAmount; }
    public void setConfirmedAmount(Long confirmedAmount) { this.confirmedAmount = confirmedAmount; }
    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
