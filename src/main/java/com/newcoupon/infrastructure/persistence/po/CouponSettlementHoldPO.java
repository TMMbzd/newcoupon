package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 券侧应付冻结 PO（V47，4B 风控挂点）。
 *
 * <p><b>语义边界（迁移列注释同源）</b>：hold 只改主体账单的应付口径
 * （{@code payable = discount_total - hold_amount}），**不动真实资金、不影响用户退款**。
 * 金额单位分，恒正；{@code template_id} 为空 = 主体级（该主体每个模板行的应付都扣这笔），
 * 非空 = 模板级（只扣对应模板行）。
 *
 * <p>状态机（服务层 {@code SettlementHoldService} 用条件 UPDATE 强制）：
 * <pre>
 *            release()            void()
 *   ACTIVE ────────────▶ RELEASED（终态）
 *     │
 *     └────────────────▶ VOIDED（终态）
 *   RELEASED ↔ VOIDED 互不可迁移；终态重放=幂等成功返回原状态
 * </pre>
 */
@TableName("coupon_settlement_hold")
public class CouponSettlementHoldPO {

    @TableId(type = IdType.INPUT)   // 雪花ID，服务层赋值
    private Long id;
    private String holdNo;
    private String appId;
    private String settlePartyId;
    private Long templateId;
    private Long amount;
    private String reason;
    /** RISK_RULE=风控规则（4B 接入）/ MANUAL=人工 */
    private String source;
    /** ACTIVE / RELEASED / VOIDED */
    private String status;
    private String createdBy;
    private String releasedBy;
    private LocalDateTime createdAt;
    private LocalDateTime releasedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getHoldNo() { return holdNo; }
    public void setHoldNo(String holdNo) { this.holdNo = holdNo; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getSettlePartyId() { return settlePartyId; }
    public void setSettlePartyId(String settlePartyId) { this.settlePartyId = settlePartyId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getReleasedBy() { return releasedBy; }
    public void setReleasedBy(String releasedBy) { this.releasedBy = releasedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }
}
