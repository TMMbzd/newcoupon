package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 租户生命周期事件 PO，对应 open_app_lifecycle_event（V45，V6 2B）。
 * 未分片（与 open_app 同域，走分片规则的默认库路由）。
 */
@TableName("open_app_lifecycle_event")
public class OpenAppLifecycleEventPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    /** 迁移前状态；导出类动作（from==to）记录当前状态 */
    private String fromStatus;
    /** 迁移后状态 */
    private String toStatus;
    /** 动作名（AppLifecycle.ACTION_*；导出用 data_export） */
    private String action;
    /** 操作者：会话用户名；旧令牌/系统触发记 "admin" */
    private String operator;
    /** 操作原因（运营填写） */
    private String reason;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
