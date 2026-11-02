package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 预算账户 PO（V42，V6 阶段 1C）。一行 = 一个（范围，周期）的钱袋子：
 * 余额三列 total/reserved/consumed 是**快照**，并发安全不靠本对象读-改-写，
 * 靠 Mapper 的条件 UPDATE（reserved+consumed+amount<=total）在数据库层原子完成。
 */
@TableName("coupon_budget_account")
public class CouponBudgetAccountPO {

    /** 范围类型：1=活动级（MVP 唯一组合）；2租户/3模板列占位 */
    public static final int SCOPE_ACTIVITY = 1;
    /** 周期类型：1=总量预算（MVP 唯一组合）；2日/3月列占位 */
    public static final int PERIOD_TOTAL = 1;
    /** 总量预算的周期键恒为 TOTAL（日/月预算将来用 2026-11-03 这类自然键切换，不清零） */
    public static final String PERIOD_KEY_TOTAL = "TOTAL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer scopeType;
    private Long scopeId;
    private String appId;
    private Integer periodType;
    private String periodKey;
    private Long total;        // 预算总额（分）
    private Long reserved;     // 已预占未终态（分）
    private Long consumed;     // 已确认消耗（分）
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getScopeType() { return scopeType; }
    public void setScopeType(Integer scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public Integer getPeriodType() { return periodType; }
    public void setPeriodType(Integer periodType) { this.periodType = periodType; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public Long getTotal() { return total; }
    public void setTotal(Long total) { this.total = total; }
    public Long getReserved() { return reserved; }
    public void setReserved(Long reserved) { this.reserved = reserved; }
    public Long getConsumed() { return consumed; }
    public void setConsumed(Long consumed) { this.consumed = consumed; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
