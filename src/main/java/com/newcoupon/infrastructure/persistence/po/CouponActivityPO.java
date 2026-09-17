package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 营销活动 PO（V9 P2a，预算-活动-批次三层模型的事实源）。
 * 一行 = 一个活动（含预算上限）；一个活动下可建多个券模板（批次）。
 */
@TableName("coupon_activity")
public class CouponActivityPO {

    @TableId(type = IdType.AUTO)   // 小配置表不分片，保留自增
    private Long id;
    private String activityCode;
    private String name;
    private Long budgetAmount;     // 预算金额（分）
    private Integer funderType;    // 1平台 2商家 3联合
    private Integer funderRatio;   // 联合时平台承担百分比
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;        // 0草稿 1进行中 2已结束 3已取消
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
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
