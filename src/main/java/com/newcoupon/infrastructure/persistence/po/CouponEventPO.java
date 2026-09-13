package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 券事件 PO，对应表 coupon_event（唯一事实源，append-only）。
 */
@TableName("coupon_event")
public class CouponEventPO {

    @TableId(type = IdType.INPUT)   // 雪花ID显式赋值（ADR-0010批次二）
    private Long id;

    private String eventNo;
    private Integer eventType;
    private Long templateId;
    private Long userCouponId;
    private Long userId;
    private String orderNo;
    private Long amount;
    private LocalDateTime occurTime;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventNo() { return eventNo; }
    public void setEventNo(String eventNo) { this.eventNo = eventNo; }
    public Integer getEventType() { return eventType; }
    public void setEventType(Integer eventType) { this.eventType = eventType; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getUserCouponId() { return userCouponId; }
    public void setUserCouponId(Long userCouponId) { this.userCouponId = userCouponId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public LocalDateTime getOccurTime() { return occurTime; }
    public void setOccurTime(LocalDateTime occurTime) { this.occurTime = occurTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
