package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 库存漂移日志 PO，对应表 stock_drift_log（V3，ADR-0008）——只插入不更新 */
@TableName("stock_drift_log")
public class StockDriftLogPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private LocalDateTime checkTime;
    private Long redisIssued;
    private Long redisDbi;
    private Long redisPending;
    private Long dbTotal;
    private Long dbIssued;
    private Long couponCount;
    private Long redisStock;
    private Long delta;
    private String action;
    private String detail;
    private Long redisVoid;        // V8：守恒式第四态读数
    private String disposition;    // V8：处置回填（true/false误报/manual）
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public LocalDateTime getCheckTime() { return checkTime; }
    public void setCheckTime(LocalDateTime checkTime) { this.checkTime = checkTime; }
    public Long getRedisIssued() { return redisIssued; }
    public void setRedisIssued(Long redisIssued) { this.redisIssued = redisIssued; }
    public Long getRedisDbi() { return redisDbi; }
    public void setRedisDbi(Long redisDbi) { this.redisDbi = redisDbi; }
    public Long getRedisPending() { return redisPending; }
    public void setRedisPending(Long redisPending) { this.redisPending = redisPending; }
    public Long getDbTotal() { return dbTotal; }
    public void setDbTotal(Long dbTotal) { this.dbTotal = dbTotal; }
    public Long getDbIssued() { return dbIssued; }
    public void setDbIssued(Long dbIssued) { this.dbIssued = dbIssued; }
    public Long getCouponCount() { return couponCount; }
    public void setCouponCount(Long couponCount) { this.couponCount = couponCount; }
    public Long getRedisStock() { return redisStock; }
    public void setRedisStock(Long redisStock) { this.redisStock = redisStock; }
    public Long getDelta() { return delta; }
    public void setDelta(Long delta) { this.delta = delta; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public Long getRedisVoid() { return redisVoid; }
    public void setRedisVoid(Long redisVoid) { this.redisVoid = redisVoid; }
    public String getDisposition() { return disposition; }
    public void setDisposition(String disposition) { this.disposition = disposition; }
}
