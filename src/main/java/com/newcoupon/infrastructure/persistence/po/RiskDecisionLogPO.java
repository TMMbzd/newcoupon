package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 风控决策日志 PO（V48，V6 4A 最小版）。
 *
 * <p><b>它是"可回放"的物理根基</b>：每次决策（开关开时）一行，所有被评估规则的
 * 因子快照（factorsJson，LinkedHashMap 序）与产出动作的规则（matchedRules 逗号分隔）
 * 全部落库。回放端点按行内快照只读重算当前规则集——不改本行（V6 原文语义：
 * 回放用于误杀复盘与规则调参验证，历史必须保持原样）。
 *
 * <p>开关关时不写行（回归零变化：关=热路径零额外 IO，用例钉住）。
 */
@TableName("risk_decision_log")
public class RiskDecisionLogPO {

    @TableId(type = IdType.INPUT)   // 雪花ID，服务层赋值
    private Long id;
    /** RECEIVE / LOCK / CONFIRM / REFUND（RiskScene.name()；后三者刀 2 接线） */
    private String scene;
    /** 决策归属租户（领券=券模板归属 app；null=无租户上下文） */
    private String appId;
    private Long userId;
    /** X-Device-Id 透传值（客户端可换——透传维度不是可信指纹） */
    private String deviceId;
    /** 容器解析的客户端 IP（与 RiskControlFilter 同一提取规则） */
    private String ip;
    /** 因子快照 JSON（各被评估规则的输入与结果） */
    private String factorsJson;
    /** 命中（产出动作）的规则 ID 逗号分隔 */
    private String matchedRules;
    /** PASS / CHALLENGE / REVIEW / REJECT（RiskAction.name()） */
    private String action;
    /** 1=因子不完整仍出决策（fail-open：设备头缺失 / 设备计数 Redis 不可用） */
    private Integer degraded;
    private String traceId;
    /** 幂等键（领券=ReceiveRequest.requestId） */
    private String requestId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getFactorsJson() { return factorsJson; }
    public void setFactorsJson(String factorsJson) { this.factorsJson = factorsJson; }
    public String getMatchedRules() { return matchedRules; }
    public void setMatchedRules(String matchedRules) { this.matchedRules = matchedRules; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Integer getDegraded() { return degraded; }
    public void setDegraded(Integer degraded) { this.degraded = degraded; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
