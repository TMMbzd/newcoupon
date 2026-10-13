package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** T+1 日账单（按模板日汇总，uk(bill_date, template_id) 重跑幂等覆盖） */
@TableName("coupon_daily_bill")
public class CouponDailyBillPO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate billDate;
    private Long templateId;
    private Integer confirmCount;   // 核销行数（正分账行）
    private Integer refundCount;    // 退款冲回行数（负分账行）
    private Long discountTotal;     // 净优惠总额（分）= 平台 + 商家承担
    private Long platformBurden;    // 平台承担净额（分）
    private Long merchantBurden;    // 商家承担净额（分）
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
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
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
