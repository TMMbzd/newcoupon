package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 开放接口调用审计 PO，对应 open_api_audit（V14，S1）——只插入不更新 */
@TableName("open_api_audit")
public class OpenApiAuditPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String appId;
    private Integer verified;      // 0 未通过验签 1 已通过
    private String resource;
    private String method;
    private String path;
    private Integer httpStatus;
    private Integer code;
    private Integer costMs;
    private String clientIp;
    private Long userId;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Integer getVerified() { return verified; }
    public void setVerified(Integer verified) { this.verified = verified; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public Integer getCode() { return code; }
    public void setCode(Integer code) { this.code = code; }
    public Integer getCostMs() { return costMs; }
    public void setCostMs(Integer costMs) { this.costMs = costMs; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
