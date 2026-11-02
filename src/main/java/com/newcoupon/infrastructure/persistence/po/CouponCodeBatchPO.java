package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 兑换码批次头（V41，V6 阶段 1B）。
 *
 * <p>明文不落库：批次只记元数据（模板、数量、类型、导出状态），
 * 码明文只存在于建批响应与首次导出，code 行只有 HMAC 哈希。
 */
@TableName("coupon_code_batch")
public class CouponCodeBatchPO {

    /** 0 启用（可兑换） */
    public static final int STATUS_ENABLED = 0;
    /** 1 停用（整批不可兑换——撤销活动/风控下架） */
    public static final int STATUS_DISABLED = 1;

    /** 0 未导出 */
    public static final int EXPORT_NOT_DONE = 0;
    /** 1 已导出（明文 CSV 只允许一次，再请求 → 4002） */
    public static final int EXPORT_DONE = 1;

    /** 1 手工码：16 字符，含 1 位校验符（输错一位绝大多数在校验位被拦下） */
    public static final int TYPE_MANUAL = 1;
    /** 2 二维码：26 字符，无校验符（扫码不存在手误，长 alphabet 提高防猜密度） */
    public static final int TYPE_QR = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String batchNo;
    private String appId;
    private Long templateId;
    private Integer codeType;
    private Integer total;
    private Integer used;
    private Integer status;
    private Integer exportState;
    private String requestId;
    private String operator;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getCodeType() { return codeType; }
    public void setCodeType(Integer codeType) { this.codeType = codeType; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
    public Integer getUsed() { return used; }
    public void setUsed(Integer used) { this.used = used; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getExportState() { return exportState; }
    public void setExportState(Integer exportState) { this.exportState = exportState; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
