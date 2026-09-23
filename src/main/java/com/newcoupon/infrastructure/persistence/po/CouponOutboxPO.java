package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 发件箱 PO（V11 P2d，ADR-0011）。一行 = 一条待投递/已投递/死信的事件消息。
 * 与 coupon_event 同事务写入（event_no 唯一关联）；本表承载**可变的投递运营状态**，
 * 事件表保持只追加。消费方幂等锚点 = event_no（内嵌于 payload）。
 * 单表不分片（随 coupon_event 同库同决策：投递器单线程轮询，无分片诉求）。
 */
@TableName("coupon_outbox")
public class CouponOutboxPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventNo;
    private Integer eventType;
    private String payload;
    private Integer status;         // 0待投递 1已投递 2死信
    private Integer retryCount;
    private LocalDateTime nextScanTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DISPATCHED = 1;
    public static final int STATUS_DEAD = 2;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventNo() { return eventNo; }
    public void setEventNo(String eventNo) { this.eventNo = eventNo; }
    public Integer getEventType() { return eventType; }
    public void setEventType(Integer eventType) { this.eventType = eventType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getNextScanTime() { return nextScanTime; }
    public void setNextScanTime(LocalDateTime nextScanTime) { this.nextScanTime = nextScanTime; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
