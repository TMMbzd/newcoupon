package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 支付后核销异常 PO（V23，环 2）。
 *
 * <p>一行 = 一份占用单的一次异常。唯一键 usage_no：同一份合同只进入异常一次
 * （CAS 0→7 保证）；resolve_status 留痕人工是“按快照核销”还是“确认退款后释放”。
 */
@TableName("coupon_confirm_exception")
public class CouponConfirmExceptionPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String usageNo;
    private String appId;
    private Long userId;
    private Long userCouponId;
    private String orderNo;
    private String pricingId;
    private String reason;
    /** PENDING / CONFIRMED / RELEASED */
    private String resolveStatus;
    private String resolveReason;
    private LocalDateTime resolveTime;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsageNo() { return usageNo; }
    public void setUsageNo(String usageNo) { this.usageNo = usageNo; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getUserCouponId() { return userCouponId; }
    public void setUserCouponId(Long userCouponId) { this.userCouponId = userCouponId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getPricingId() { return pricingId; }
    public void setPricingId(String pricingId) { this.pricingId = pricingId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getResolveStatus() { return resolveStatus; }
    public void setResolveStatus(String resolveStatus) { this.resolveStatus = resolveStatus; }
    public String getResolveReason() { return resolveReason; }
    public void setResolveReason(String resolveReason) { this.resolveReason = resolveReason; }
    public LocalDateTime getResolveTime() { return resolveTime; }
    public void setResolveTime(LocalDateTime resolveTime) { this.resolveTime = resolveTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
