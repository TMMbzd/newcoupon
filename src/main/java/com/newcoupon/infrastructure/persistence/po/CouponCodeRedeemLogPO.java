package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 兑换流水行（V41，V6 阶段 1B）：一次兑换尝试一行，成功/失败都记。
 * 防枚举口径下"真实失败原因"只进这张表，不进响应。
 */
@TableName("coupon_code_redeem_log")
public class CouponCodeRedeemLogPO {

    /** 1 成功（已发券，含幂等重放） */
    public static final int RESULT_SUCCESS = 1;
    /** 2 失败（统一文案对外；真实原因在 fail_reason） */
    public static final int RESULT_FAIL = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    private Long codeId;
    private String appId;
    private Long userId;
    private String requestId;
    private Integer result;
    private String failReason;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getCodeId() { return codeId; }
    public void setCodeId(Long codeId) { this.codeId = codeId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getResult() { return result; }
    public void setResult(Integer result) { this.result = result; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
