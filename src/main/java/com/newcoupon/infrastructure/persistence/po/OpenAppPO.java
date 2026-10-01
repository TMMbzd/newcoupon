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
}
