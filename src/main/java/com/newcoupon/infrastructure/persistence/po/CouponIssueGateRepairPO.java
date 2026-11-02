package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Redis gate 修复队列（V36）。
 *
 * <p>为什么需要它：请求路径只做一次单飞修复；修复失败或没抢到许可时，把问题登记到数据库。
 * 后台任务不依赖 Redis 是否可用，按 SKIP LOCKED 领取，指数退避重试，超过上限进死信。
 */
@TableName("coupon_issue_gate_repair")
public class CouponIssueGateRepairPO {

    public static final int SCOPE_TEMPLATE = 0;
    public static final int SCOPE_USER = 1;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_DEAD = 3;

    /** 模板级问题（gate）没有日限归属，统一用这个哨兵日期，保证唯一键稳定。 */
    public static final LocalDate NO_DAY = LocalDate.of(1970, 1, 1);

    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer scopeType;
    private Long templateId;
    private Long userId;
    private LocalDate statDay;
    private Integer errorCode;
    private Integer status;
    private Integer attempts;
    private LocalDateTime nextRetryTime;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getScopeType() { return scopeType; }
    public void setScopeType(Integer scopeType) { this.scopeType = scopeType; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDate getStatDay() { return statDay; }
    public void setStatDay(LocalDate statDay) { this.statDay = statDay; }
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer errorCode) { this.errorCode = errorCode; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public LocalDateTime getNextRetryTime() { return nextRetryTime; }
    public void setNextRetryTime(LocalDateTime nextRetryTime) { this.nextRetryTime = nextRetryTime; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
