package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 券退款单（V21）：refund_no 是退款幂等锚点。
 *
 * <p>一次退款请求 = 一张退款单 + 若干退款明细（按商品行）。
 * 资金账仍落 coupon_share_detail 的正行；退款明细是独立的冲回账，
 * 日账单聚合时两表 UNION ALL（正行计核销、退款明细计冲回）。
 */
@TableName("coupon_refund_order")
public class CouponRefundOrderPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundNo;
    private String usageNo;
    private Long userCouponId;
    private Long userId;
    private Long templateId;
    private String orderNo;
    /** PARTIAL / FULL */
    private String refundType;
    private Long refundDiscountAmt;
    private Long refundPlatformAmt;
    private Long refundMerchantAmt;
    /** 请求摘要（usageNo + 排序后的行号:数量），同号异参检测 */
    private String requestDigest;
    private Integer itemCount;
    /** 整单退时券是否已退回可用（1=是） */
    private Integer couponReturned;
    /** 退款完成时的券状态（CouponStatus.name()，同号重放原样返回） */
    private String couponStatus;
    /** 退款完成时的占用单状态（UsageStatus.name()，同号重放原样返回） */
    private String usageStatus;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public String getUsageNo() { return usageNo; }
    public void setUsageNo(String usageNo) { this.usageNo = usageNo; }
    public Long getUserCouponId() { return userCouponId; }
    public void setUserCouponId(Long userCouponId) { this.userCouponId = userCouponId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getRefundType() { return refundType; }
    public void setRefundType(String refundType) { this.refundType = refundType; }
    public Long getRefundDiscountAmt() { return refundDiscountAmt; }
    public void setRefundDiscountAmt(Long refundDiscountAmt) { this.refundDiscountAmt = refundDiscountAmt; }
    public Long getRefundPlatformAmt() { return refundPlatformAmt; }
    public void setRefundPlatformAmt(Long refundPlatformAmt) { this.refundPlatformAmt = refundPlatformAmt; }
    public Long getRefundMerchantAmt() { return refundMerchantAmt; }
    public void setRefundMerchantAmt(Long refundMerchantAmt) { this.refundMerchantAmt = refundMerchantAmt; }
    public String getRequestDigest() { return requestDigest; }
    public void setRequestDigest(String requestDigest) { this.requestDigest = requestDigest; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public Integer getCouponReturned() { return couponReturned; }
    public void setCouponReturned(Integer couponReturned) { this.couponReturned = couponReturned; }
    public String getCouponStatus() { return couponStatus; }
    public void setCouponStatus(String couponStatus) { this.couponStatus = couponStatus; }
    public String getUsageStatus() { return usageStatus; }
    public void setUsageStatus(String usageStatus) { this.usageStatus = usageStatus; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
