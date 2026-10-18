package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 占用单 ↔ 价格快照绑定 PO（V23）。usage_no 唯一。 */
@TableName("coupon_pricing_usage")
public class CouponPricingUsagePO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String usageNo;
    private String pricingId;
    private String appId;
    private Long userId;
    private String orderNo;
    private Long couponId;
    private LocalDateTime boundAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsageNo() { return usageNo; }
    public void setUsageNo(String usageNo) { this.usageNo = usageNo; }
    public String getPricingId() { return pricingId; }
    public void setPricingId(String pricingId) { this.pricingId = pricingId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public LocalDateTime getBoundAt() { return boundAt; }
    public void setBoundAt(LocalDateTime boundAt) { this.boundAt = boundAt; }
}
