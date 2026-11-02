package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 异步发放任务（V27，ADR-0019）。它是“已受理请求”的 WAL，不是库存账本：
 * 库存最终仍由 {@code coupon_stock_shard} 的条件更新裁决，任务表只保证异步链路不丢。
 *
 * <p>唯一键 {@code (app_id, user_id, request_id)} 是异步幂等锚点；跨租户同 user+requestId
 * 允许各有一行。券表 {@code uk_idem} 在 V28 也补了 app_id，两层一起保证最终不串租户。
 */
@TableName("coupon_issue_task")
public class CouponIssueTaskPO {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_DEAD = 4;

    /** gate 释放状态（V31）：成功任务永不释放，终态任务由补偿任务幂等释放。 */
    public static final int GATE_PENDING = 0;
    public static final int GATE_ISSUED = 1;
    public static final int GATE_RELEASED = 2;
    public static final int GATE_RETRY = 3;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private Long userId;
    private Long templateId;
    private String requestId;
    /** 受理日：日限归属日。worker 必须用它，不能用执行时的 LocalDate.now()，否则跨天会漂移。 */
    private java.time.LocalDate statDay;
    private Integer seq;
    private String source;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextScanTime;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long couponId;
    private Integer errorCode;
    private String errorMsg;
    /** V31：0待处理/1已签发(不释放)/2已释放/3待重试 */
    private Integer gateReleaseState;
    private LocalDateTime gateNextRetryTime;
    /** 受理时快照：释放时据此判断哪些 Redis 计数真正预扣过 */
    private Integer gateMaxPerUser;
    private Integer gatePerUserDaily;
    private Long gateDailyLimit;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public java.time.LocalDate getStatDay() { return statDay; }
    public void setStatDay(java.time.LocalDate statDay) { this.statDay = statDay; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getNextScanTime() { return nextScanTime; }
    public void setNextScanTime(LocalDateTime nextScanTime) { this.nextScanTime = nextScanTime; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer errorCode) { this.errorCode = errorCode; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Integer getGateReleaseState() { return gateReleaseState; }
    public void setGateReleaseState(Integer gateReleaseState) { this.gateReleaseState = gateReleaseState; }
    public LocalDateTime getGateNextRetryTime() { return gateNextRetryTime; }
    public void setGateNextRetryTime(LocalDateTime gateNextRetryTime) { this.gateNextRetryTime = gateNextRetryTime; }
    public Integer getGateMaxPerUser() { return gateMaxPerUser; }
    public void setGateMaxPerUser(Integer gateMaxPerUser) { this.gateMaxPerUser = gateMaxPerUser; }
    public Integer getGatePerUserDaily() { return gatePerUserDaily; }
    public void setGatePerUserDaily(Integer gatePerUserDaily) { this.gatePerUserDaily = gatePerUserDaily; }
    public Long getGateDailyLimit() { return gateDailyLimit; }
    public void setGateDailyLimit(Long gateDailyLimit) { this.gateDailyLimit = gateDailyLimit; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
