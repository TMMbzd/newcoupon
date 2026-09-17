package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 成本分摊明细 PO（V9 P2a）——核销事务原子写入。
 * 一行 = 一个占用单×一个商品行×一个承担主体的分摊结果。
 * 守恒：同一 usage_no 的 SUM(share_total) = 占用单的 discount_amt。
 * user_id 是分片路由键（P5 实施时 V10 建 64 物理分表；先在单表跑通再拆）。
 */
@TableName("coupon_share_detail")
public class CouponShareDetailPO {

    @TableId(type = IdType.INPUT)   // 雪花ID（将分片）
    private Long id;
    private Long userCouponId;
    private Long userId;
    private Long templateId;
    private String usageNo;
    private String orderNo;
    private String orderLineNo;
    private Long orderLineAmount;
    private Long platformBurden;   // 平台承担金额（分）
    private Long merchantBurden;   // 商家承担金额（分）
    private Long shareTotal;       // = platform + merchant
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserCouponId() { return userCouponId; }
    public void setUserCouponId(Long userCouponId) { this.userCouponId = userCouponId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getUsageNo() { return usageNo; }
    public void setUsageNo(String usageNo) { this.usageNo = usageNo; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getOrderLineNo() { return orderLineNo; }
    public void setOrderLineNo(String orderLineNo) { this.orderLineNo = orderLineNo; }
    public Long getOrderLineAmount() { return orderLineAmount; }
    public void setOrderLineAmount(Long orderLineAmount) { this.orderLineAmount = orderLineAmount; }
    public Long getPlatformBurden() { return platformBurden; }
    public void setPlatformBurden(Long platformBurden) { this.platformBurden = platformBurden; }
    public Long getMerchantBurden() { return merchantBurden; }
    public void setMerchantBurden(Long merchantBurden) { this.merchantBurden = merchantBurden; }
    public Long getShareTotal() { return shareTotal; }
    public void setShareTotal(Long shareTotal) { this.shareTotal = shareTotal; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
