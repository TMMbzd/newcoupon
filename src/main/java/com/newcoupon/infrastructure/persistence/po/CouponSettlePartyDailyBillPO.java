package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 按结算主体的 T+1 日账单 PO（V24，环 3）。
 *
 * <p>唯一键 `(bill_date, app_id, settle_party_id, template_id)`：同一天同一租户
 * 同一主体同一模板只有一行；重跑 upsert 覆盖，不累加。
 * 金额是净额：正分账行 - 退款明细 - 旧负分账行。
 */
@TableName("coupon_settle_party_daily_bill")
public class CouponSettlePartyDailyBillPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate billDate;
    private String appId;
    private String settlePartyId;
    private Long templateId;
    private Integer confirmCount;
    private Integer refundCount;
    private Long discountTotal;
    private Long platformBurden;
    private Long merchantBurden;
    /** V47：生成时命中的 ACTIVE hold 扣减额（分）——主体级 hold 对每个模板行全额扣减 */
    private Long holdAmount;
    /** V47：应付=discount_total-hold_amount；负数如实展示=未释放 hold 超当期净额 */
    private Long payableAmount;
    /** V47：当日质量排除的分账行数（可观测；金额不计入本行） */
    private Integer ineligibleShareCount;
    /** V47：当日质量排除的退款明细行数（可观测） */
    private Integer ineligibleRefundCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getSettlePartyId() { return settlePartyId; }
    public void setSettlePartyId(String settlePartyId) { this.settlePartyId = settlePartyId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getConfirmCount() { return confirmCount; }
    public void setConfirmCount(Integer confirmCount) { this.confirmCount = confirmCount; }
    public Integer getRefundCount() { return refundCount; }
    public void setRefundCount(Integer refundCount) { this.refundCount = refundCount; }
    public Long getDiscountTotal() { return discountTotal; }
    public void setDiscountTotal(Long discountTotal) { this.discountTotal = discountTotal; }
    public Long getPlatformBurden() { return platformBurden; }
    public void setPlatformBurden(Long platformBurden) { this.platformBurden = platformBurden; }
    public Long getMerchantBurden() { return merchantBurden; }
    public void setMerchantBurden(Long merchantBurden) { this.merchantBurden = merchantBurden; }
    public Long getHoldAmount() { return holdAmount; }
    public void setHoldAmount(Long holdAmount) { this.holdAmount = holdAmount; }
    public Long getPayableAmount() { return payableAmount; }
    public void setPayableAmount(Long payableAmount) { this.payableAmount = payableAmount; }
    public Integer getIneligibleShareCount() { return ineligibleShareCount; }
    public void setIneligibleShareCount(Integer ineligibleShareCount) { this.ineligibleShareCount = ineligibleShareCount; }
    public Integer getIneligibleRefundCount() { return ineligibleRefundCount; }
    public void setIneligibleRefundCount(Integer ineligibleRefundCount) { this.ineligibleRefundCount = ineligibleRefundCount; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
