package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 接入方 PO，对应 open_app（V14，S1）——单表（分片规则里走 !SINGLE "*.*"），不分片 */
@TableName("open_app")
public class OpenAppPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private String appName;
    private Integer status;
    private Integer qpsLimit;
    private Integer dailyQuota;
    private String contact;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** V45：环境（PROD/SANDBOX）——资金边界守卫的依据 */
    private String environment;
    /** V45：生命周期状态（字符串即 AppLifecycle 枚举名，解析在领域层） */
    private String lifecycleStatus;
    /** V45：注销完成时刻（confirm_close 写入） */
    private LocalDateTime closedAt;
    /** V45：数据保留截止（只写不做——清理是 runbook 手动事项） */
    private java.time.LocalDate retentionUntil;
    /** V45：法务保留（1=禁止删除） */
    private Integer legalHold;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getQpsLimit() { return qpsLimit; }
    public void setQpsLimit(Integer qpsLimit) { this.qpsLimit = qpsLimit; }
    public Integer getDailyQuota() { return dailyQuota; }
    public void setDailyQuota(Integer dailyQuota) { this.dailyQuota = dailyQuota; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(String lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public java.time.LocalDate getRetentionUntil() { return retentionUntil; }
    public void setRetentionUntil(java.time.LocalDate retentionUntil) { this.retentionUntil = retentionUntil; }
    public Integer getLegalHold() { return legalHold; }
    public void setLegalHold(Integer legalHold) { this.legalHold = legalHold; }
}
