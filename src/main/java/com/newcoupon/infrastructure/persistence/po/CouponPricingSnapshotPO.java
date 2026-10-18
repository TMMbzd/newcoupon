package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 试算价格快照 PO（V23，环 2）。独立非分片表。
 *
 * <p>payload 是 JSON 文本（MEDIUMTEXT），领域/应用层自行用 Jackson 序列化；
 * 不引入 MyBatis TypeHandler，与仓里 V16/V18 的 JSON-in-TEXT 惯例一致。
 */
@TableName("coupon_pricing_snapshot")
public class CouponPricingSnapshotPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String pricingId;
    private String appId;
    private Long userId;
    private String orderNo;
    private String couponSet;
    private String requestDigest;
    private String liveDigest;
    private String payload;
    private Integer payloadVersion;
    private String engineVersion;
    private Integer payloadBytes;
    private Integer lineCount;
    private Integer appliedCouponCount;
    private Long originalAmount;
    private Long discountAmount;
    private Long finalAmount;
    private LocalDateTime expiresAt;
    private LocalDateTime lastBoundAt;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPricingId() { return pricingId; }
    public void setPricingId(String pricingId) { this.pricingId = pricingId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getCouponSet() { return couponSet; }
    public void setCouponSet(String couponSet) { this.couponSet = couponSet; }
    public String getRequestDigest() { return requestDigest; }
    public void setRequestDigest(String requestDigest) { this.requestDigest = requestDigest; }
    public String getLiveDigest() { return liveDigest; }
    public void setLiveDigest(String liveDigest) { this.liveDigest = liveDigest; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Integer getPayloadVersion() { return payloadVersion; }
    public void setPayloadVersion(Integer payloadVersion) { this.payloadVersion = payloadVersion; }
    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }
    public Integer getPayloadBytes() { return payloadBytes; }
    public void setPayloadBytes(Integer payloadBytes) { this.payloadBytes = payloadBytes; }
    public Integer getLineCount() { return lineCount; }
    public void setLineCount(Integer lineCount) { this.lineCount = lineCount; }
    public Integer getAppliedCouponCount() { return appliedCouponCount; }
    public void setAppliedCouponCount(Integer appliedCouponCount) { this.appliedCouponCount = appliedCouponCount; }
    public Long getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(Long originalAmount) { this.originalAmount = originalAmount; }
    public Long getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(Long discountAmount) { this.discountAmount = discountAmount; }
    public Long getFinalAmount() { return finalAmount; }
    public void setFinalAmount(Long finalAmount) { this.finalAmount = finalAmount; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getLastBoundAt() { return lastBoundAt; }
    public void setLastBoundAt(LocalDateTime lastBoundAt) { this.lastBoundAt = lastBoundAt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
