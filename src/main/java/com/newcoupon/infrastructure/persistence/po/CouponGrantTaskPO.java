package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 批量发放任务（批次头，V39，V6 阶段 1A）。
 *
 * <p>它是编排元数据不是库存账本：库存权威仍在 {@code coupon_stock_shard} 的条件更新，
 * 每用户发券复用 C1 领取链路（幂等键 grant-{taskNo}-{seq}）。
 *
 * <p>计数守恒不变量：{@code total = success + fail + skip + pending}，由
 * "item 状态 CAS + 计数累加同事务"维护（见 {@code GrantTaskTxService}）；
 * 取消的 item 归入 skip——skip 语义=未发放。
 */
@TableName("coupon_grant_task")
public class CouponGrantTaskPO {

    /** 0 待执行（刚受理，还没有任何 chunk 被领取） */
    public static final int STATUS_PENDING = 0;
    /** 1 执行中（首个 chunk 被领取时迁移） */
    public static final int STATUS_RUNNING = 1;
    /** 2 全部 chunk 终态且未被取消（fail&gt;0 仍是 SUCCESS，明细可查） */
    public static final int STATUS_SUCCESS = 2;
    /** 3 失败（本刀无全局失败触发路径，状态值先占位） */
    public static final int STATUS_FAILED = 3;
    /** 4 取消中（受理取消；剩余 chunk 收尾后转 CANCELED） */
    public static final int STATUS_CANCELING = 4;
    /** 5 已取消 */
    public static final int STATUS_CANCELED = 5;

    /** 批量发放（1A 既有入口）：调用方给一批 userId，逐人发一张 */
    public static final int GRANT_TYPE_BATCH = 1;
    /** 定向发放（阶段 6 补齐）：调用方指定 userId 列表，逐人发一张；与批量共用全链 */
    public static final int GRANT_TYPE_TARGETED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String appId;
    private Long templateId;
    /** 1=批量 2=定向（两型共用 task/chunk/item/worker，仅受理语义标记不同） */
    private Integer grantType;
    /** 1=API（本刀仅开放/内部 API） */
    private Integer sourceType;
    private String requestId;
    private Long totalCount;
    private Long successCount;
    private Long failCount;
    private Long skipCount;
    private Long pendingCount;
    private Integer status;
    /** 定时开始时间（本刀只建列不实现定时，恒为 NULL） */
    private LocalDateTime scheduleTime;
    /** 每秒发放上限；0=不限（MVP：worker 在 item 间 sleep） */
    private Integer rateLimit;
    /** 单任务最大并发 chunk 数（本刀=1，列先占位） */
    private Integer maxConcurrency;
    private String operator;
    /** 乐观锁版本；状态机 CAS 的 UPDATE 同步 version=version+1 */
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getGrantType() { return grantType; }
    public void setGrantType(Integer grantType) { this.grantType = grantType; }
    public Integer getSourceType() { return sourceType; }
    public void setSourceType(Integer sourceType) { this.sourceType = sourceType; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getTotalCount() { return totalCount; }
    public void setTotalCount(Long totalCount) { this.totalCount = totalCount; }
    public Long getSuccessCount() { return successCount; }
    public void setSuccessCount(Long successCount) { this.successCount = successCount; }
    public Long getFailCount() { return failCount; }
    public void setFailCount(Long failCount) { this.failCount = failCount; }
    public Long getSkipCount() { return skipCount; }
    public void setSkipCount(Long skipCount) { this.skipCount = skipCount; }
    public Long getPendingCount() { return pendingCount; }
    public void setPendingCount(Long pendingCount) { this.pendingCount = pendingCount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getScheduleTime() { return scheduleTime; }
    public void setScheduleTime(LocalDateTime scheduleTime) { this.scheduleTime = scheduleTime; }
    public Integer getRateLimit() { return rateLimit; }
    public void setRateLimit(Integer rateLimit) { this.rateLimit = rateLimit; }
    public Integer getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(Integer maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
