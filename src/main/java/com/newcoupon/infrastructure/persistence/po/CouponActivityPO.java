package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 营销活动 PO（V9 P2a 建表，V6 阶段 1C 激活）。
 * 一行 = 一个活动（含预算上限）；一个活动下可建多个券模板（批次）。
 * 1C 补归属/开关/创建人/版本四列：budget_enabled=0（默认）时发放链路完全不碰预算表。
 */
@TableName("coupon_activity")
public class CouponActivityPO {

    /** 预算闸门关：发放不受预算约束（1C 之前的存量行为） */
    public static final int BUDGET_DISABLED = 0;
    /** 预算闸门开：同步领取/兑换码/批量 chunk 都要先过预算预占 */
    public static final int BUDGET_ENABLED = 1;

    @TableId(type = IdType.AUTO)   // 小配置表不分片，保留自增
    private Long id;
    private String activityCode;
    private String name;
    private Long budgetAmount;     // 预算金额（分）——首建预算账户的 total；此后账户行是权威
    private Integer funderType;    // 1平台 2商家 3联合
    private Integer funderRatio;   // 联合时平台承担百分比
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;        // 0草稿 1进行中 2已结束 3已取消
    private String appId;          // V42：归属接入方（内部面创建=内置应用 1）
    private Integer budgetEnabled; // V42：预算闸门开关 0关 1开（默认关）
    private String createdBy;      // V42：创建人（审计用）
    private Long version;          // V42：乐观锁版本
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getActivityCode() { return activityCode; }
    public void setActivityCode(String activityCode) { this.activityCode = activityCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(Long budgetAmount) { this.budgetAmount = budgetAmount; }
    public Integer getFunderType() { return funderType; }
    public void setFunderType(Integer funderType) { this.funderType = funderType; }
    public Integer getFunderRatio() { return funderRatio; }
    public void setFunderRatio(Integer funderRatio) { this.funderRatio = funderRatio; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Integer getBudgetEnabled() { return budgetEnabled; }
    public void setBudgetEnabled(Integer budgetEnabled) { this.budgetEnabled = budgetEnabled; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
