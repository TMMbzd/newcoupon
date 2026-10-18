package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 券退款明细（V21）：一行商品一条，记录本次退了多少数量、冲回多少优惠与各方出资。 */
@TableName("coupon_refund_detail")
public class CouponRefundDetailPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundNo;
    private String usageNo;
    private String orderLineNo;
    private Long userCouponId;
    private Long userId;
    private Long templateId;
    /** V22：租户归属（从分账行复制） */
    private String appId;
    /** V22：结算主体（从分账行复制，不读退款请求） */
    private String settlePartyId;
    private Integer refundQty;
    private Long refundDiscountAmt;
    private Long refundPlatformAmt;
    private Long refundMerchantAmt;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public String getUsageNo() { return usageNo; }
    public void setUsageNo(String usageNo) { this.usageNo = usageNo; }
    public String getOrderLineNo() { return orderLineNo; }
    public void setOrderLineNo(String orderLineNo) { this.orderLineNo = orderLineNo; }
    public Long getUserCouponId() { return userCouponId; }
    public void setUserCouponId(Long userCouponId) { this.userCouponId = userCouponId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getSettlePartyId() { return settlePartyId; }
    public void setSettlePartyId(String settlePartyId) { this.settlePartyId = settlePartyId; }
    public Integer getRefundQty() { return refundQty; }
    public void setRefundQty(Integer refundQty) { this.refundQty = refundQty; }
    public Long getRefundDiscountAmt() { return refundDiscountAmt; }
    public void setRefundDiscountAmt(Long refundDiscountAmt) { this.refundDiscountAmt = refundDiscountAmt; }
    public Long getRefundPlatformAmt() { return refundPlatformAmt; }
    public void setRefundPlatformAmt(Long refundPlatformAmt) { this.refundPlatformAmt = refundPlatformAmt; }
    public Long getRefundMerchantAmt() { return refundMerchantAmt; }
    public void setRefundMerchantAmt(Long refundMerchantAmt) { this.refundMerchantAmt = refundMerchantAmt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
