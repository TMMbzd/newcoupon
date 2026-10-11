package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 操作审计日志 PO（第 1 环 1-1 项）。
 *
 * <p>六要素对应：appId/userId（谁）、createdAt（何时）、ip（何处）、
 * action/resourceType/resourceId（何事）、beforeSnapshot/afterSnapshot（前后值）、traceId（链路）。
 *
 * <p>哈希链：recordHash = SHA-256(全部字段拼接 + prevHash)；
 * 首条记录的 prevHash = SHA-256("AUDIT_GENESIS")。
 * 篡改任何字段 → 重算 hash 与存储值不匹配；删除任何记录 → 后续 prevHash 断链。
 */
@TableName("audit_log")
public class AuditLogPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String appId;
    private Long userId;
    private String traceId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String ip;
    private Integer resultCode;
    private String prevHash;
    private String recordHash;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getBeforeSnapshot() { return beforeSnapshot; }
    public void setBeforeSnapshot(String beforeSnapshot) { this.beforeSnapshot = beforeSnapshot; }
    public String getAfterSnapshot() { return afterSnapshot; }
    public void setAfterSnapshot(String afterSnapshot) { this.afterSnapshot = afterSnapshot; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public Integer getResultCode() { return resultCode; }
    public void setResultCode(Integer resultCode) { this.resultCode = resultCode; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public String getRecordHash() { return recordHash; }
    public void setRecordHash(String recordHash) { this.recordHash = recordHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
