package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** C1 库存调整审计行（V26）。 */
@TableName("coupon_stock_adjust_log")
public class CouponStockAdjustLogPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private String requestId;
    private String operator;
    private String reason;
    private Long expectedTotal;
    private Long beforeTotal;
    private Long targetTotal;
    private String beforeShardsJson;
    private String afterShardsJson;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getExpectedTotal() { return expectedTotal; }
    public void setExpectedTotal(Long expectedTotal) { this.expectedTotal = expectedTotal; }
    public Long getBeforeTotal() { return beforeTotal; }
    public void setBeforeTotal(Long beforeTotal) { this.beforeTotal = beforeTotal; }
    public Long getTargetTotal() { return targetTotal; }
    public void setTargetTotal(Long targetTotal) { this.targetTotal = targetTotal; }
    public String getBeforeShardsJson() { return beforeShardsJson; }
    public void setBeforeShardsJson(String beforeShardsJson) { this.beforeShardsJson = beforeShardsJson; }
    public String getAfterShardsJson() { return afterShardsJson; }
    public void setAfterShardsJson(String afterShardsJson) { this.afterShardsJson = afterShardsJson; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
