package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 用户券 PO，对应表 user_coupon（V1 建表 + V2 增列 seq/日限，当前单表、M4 分库分表）。
 * 唯一索引两个：uk_idem(user_id, idempotent_key) 幂等终审；
 * uk_user_seq(user_id, template_id, seq) 限领 N 张强制（V2，seq=Redis 用户计数 1..N）。
 */
@TableName("user_coupon")
public class UserCouponPO {

    @TableId(type = IdType.INPUT)   // 雪花ID显式赋值（ADR-0010批次二）
    private Long id;

    private Long userId;
    private Long templateId;
    private String templateCode;
    private Long faceValue;
    private Long threshold;
    private Long discountCap;
    private LocalDateTime validEnd;
    private Integer status;
    private LocalDateTime receiveTime;
    private String idempotentKey;
    private Integer seq;
    private String appId;      // S1：归属接入方（V14，写时从模板冗余）
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public Long getFaceValue() { return faceValue; }
    public void setFaceValue(Long faceValue) { this.faceValue = faceValue; }
    public Long getThreshold() { return threshold; }
    public void setThreshold(Long threshold) { this.threshold = threshold; }
    public Long getDiscountCap() { return discountCap; }
    public void setDiscountCap(Long discountCap) { this.discountCap = discountCap; }
    public LocalDateTime getValidEnd() { return validEnd; }
    public void setValidEnd(LocalDateTime validEnd) { this.validEnd = validEnd; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getReceiveTime() { return receiveTime; }
    public void setReceiveTime(LocalDateTime receiveTime) { this.receiveTime = receiveTime; }
    public String getIdempotentKey() { return idempotentKey; }
    public void setIdempotentKey(String idempotentKey) { this.idempotentKey = idempotentKey; }
    public Integer getSeq() { return seq; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
