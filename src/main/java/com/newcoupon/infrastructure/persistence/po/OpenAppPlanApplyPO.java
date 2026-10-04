package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 预案套用记录 PO，对应 open_app_plan_apply（V16，S2 第 10 步）——快照补偿回滚的依据 */
@TableName("open_app_plan_apply")
public class OpenAppPlanApplyPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    /** BLUE / YELLOW / RED（存 {@code SwitchPlan.name()}） */
    private String planLevel;
    private String operator;
    private String reason;
    /** 套用前快照（JSON：{"配置键": 旧值 或 null}） */
    private String snapshot;
    private LocalDateTime appliedAt;
    private LocalDateTime revertedAt;
    /** 1=生效中 2=已恢复 3=被新的套用取代 */
    private Integer status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getPlanLevel() { return planLevel; }
    public void setPlanLevel(String planLevel) { this.planLevel = planLevel; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
    public LocalDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(LocalDateTime appliedAt) { this.appliedAt = appliedAt; }
    public LocalDateTime getRevertedAt() { return revertedAt; }
    public void setRevertedAt(LocalDateTime revertedAt) { this.revertedAt = revertedAt; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
