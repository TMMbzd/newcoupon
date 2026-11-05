package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 风控名单 PO（V48，V6 4A 最小版）。
 *
 * <p><b>app_id 的存储形态</b>：空串=平台级（对所有租户生效）。用空串而不是 NULL
 * 是为了让 (list_type,value_type,value,app_id) 唯一键对平台级条目也成立——
 * MySQL 唯一索引对 NULL 的语义是"NULL 互不相等"，NULL 会放行重复插入
 * （迁移 V48 列注释同源）。视图层（RiskDtos.ListView）把空串翻译回 null=平台级。
 *
 * <p>value 明文存储：管理面要显示给运营看拉黑的是谁、等值查询即业务查询；
 * 名单不是凭据（与兑换码存哈希不同），泄漏面=名单本身——HMAC 化升级路径登记 runbook。
 */
@TableName("risk_list_entry")
public class RiskListEntryPO {

    @TableId(type = IdType.INPUT)   // 雪花ID，服务层赋值
    private Long id;
    /** BLACK / WHITE / GRAY（RiskListType.name()） */
    private String listType;
    /** USER_ID / DEVICE_ID / IP（RiskValueType.name()） */
    private String valueType;
    private String value;
    /** 空串=平台级 */
    private String appId;
    private String reason;
    /** NULL=永久 */
    private LocalDateTime expiresAt;
    /** ACTIVE / DISABLED（软删，保留申诉现场） */
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getListType() { return listType; }
    public void setListType(String listType) { this.listType = listType; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
