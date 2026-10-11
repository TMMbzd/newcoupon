package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;

/**
 * 券模板持久化对象（PO，Persistence Object）——与数据库表 coupon_template 一一对应。
 *
 * <p>为什么领域模型(DO)和 PO 分开（美团 DDD 实践的 DTO/DO/PO 三层对象，docs/01a）：
 * <ul>
 *   <li>领域模型不允许沾框架注解——它要在无 Spring/MyBatis 的环境跑单测；</li>
 *   <li>表结构变化（加列/改类型）只波及 PO 和 Converter，领域与业务代码不动——
 *       这就是"存储细节被关进基础设施层"的具体收益；</li>
 *   <li>领域字段是 long/枚举（有语义），PO 面向的是列（妥协于存储）。</li>
 * </ul>
 * 代价是多一层转换代码——工业界用 MapStruct 编译期生成，本项目手写保持零魔法。
 */
@TableName("coupon_template")
public class CouponTemplatePO {

    /** 自增主键：insert 后 MyBatis-Plus 自动把生成值回填到该字段 */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String templateCode;
    private Long activityId;
    private String name;
    private Integer couponType;
    private Long faceValue;
    private Long discountCap;
    private Long threshold;
    private Long totalCount;
    /** P2b V10：已分配到桶的额度（单调不减，只被开桶 CAS 推进） */
    private Long allocated;
    private Long issuedCount;
    private Integer perUserLimit;
    private Long dailyLimit;
    private Integer perUserDailyLimit;
    private Integer validType;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    private Integer validDays;
    private Integer approveStatus;
    private Integer publishStatus;

    /**
     * 乐观锁版本号：updateById 时自动带 WHERE version=? 且 SET version=version+1。
     * ⚠️ 隐藏坑：@Version 必须同时注册 OptimisticLockerInnerInterceptor（见 MybatisPlusConfig），
     * 只加注解不配拦截器 = 乐观锁静默失效，没有任何报错——这是 MyBatis-Plus 最经典的暗坑。
     */
    @Version
    private String appId;      // S1：归属接入方（V14）
    private Integer version;

    // ---- 五维模型新增四维（第 2 环 V19）----
    private Integer scopeType;         // 0=ALL(全场) 1=CATEGORY(品类) 2=SKU(单品) — V9 列 scope_type
    private String scopeIds;           // JSON数组如 [1001,1002]；ALL时为NULL
    private Integer stackingLayer;     // 0=SHOP(店铺级) 1=PLATFORM(平台级)
    private String exclusionGroup;     // 互斥组名；NULL=不限制
    private Integer funderType;        // 1=PLATFORM(平台) 2=MERCHANT(商家) 3=SPLIT(共担) — V9 列 funder_type
    private Integer budgetRatio;       // 平台百分比 1~99；仅SPLIT时必填

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getCouponType() { return couponType; }
    public void setCouponType(Integer couponType) { this.couponType = couponType; }
    public Long getFaceValue() { return faceValue; }
    public void setFaceValue(Long faceValue) { this.faceValue = faceValue; }
    public Long getDiscountCap() { return discountCap; }
    public void setDiscountCap(Long discountCap) { this.discountCap = discountCap; }
    public Long getThreshold() { return threshold; }
    public void setThreshold(Long threshold) { this.threshold = threshold; }
    public Long getTotalCount() { return totalCount; }
    public void setTotalCount(Long totalCount) { this.totalCount = totalCount; }
    public Long getAllocated() { return allocated; }
    public void setAllocated(Long allocated) { this.allocated = allocated; }
    public Long getIssuedCount() { return issuedCount; }
    public void setIssuedCount(Long issuedCount) { this.issuedCount = issuedCount; }
    public Integer getPerUserLimit() { return perUserLimit; }
    public void setPerUserLimit(Integer perUserLimit) { this.perUserLimit = perUserLimit; }
    public Long getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(Long dailyLimit) { this.dailyLimit = dailyLimit; }
    public Integer getPerUserDailyLimit() { return perUserDailyLimit; }
    public void setPerUserDailyLimit(Integer perUserDailyLimit) { this.perUserDailyLimit = perUserDailyLimit; }
    public Integer getValidType() { return validType; }
    public void setValidType(Integer validType) { this.validType = validType; }
    public LocalDateTime getValidStart() { return validStart; }
    public void setValidStart(LocalDateTime validStart) { this.validStart = validStart; }
    public LocalDateTime getValidEnd() { return validEnd; }
    public void setValidEnd(LocalDateTime validEnd) { this.validEnd = validEnd; }
    public Integer getValidDays() { return validDays; }
    public void setValidDays(Integer validDays) { this.validDays = validDays; }
    public Integer getApproveStatus() { return approveStatus; }
    public void setApproveStatus(Integer approveStatus) { this.approveStatus = approveStatus; }
    public Integer getPublishStatus() { return publishStatus; }
    public void setPublishStatus(Integer publishStatus) { this.publishStatus = publishStatus; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getScopeType() { return scopeType; }
    public void setScopeType(Integer scopeType) { this.scopeType = scopeType; }
    public String getScopeIds() { return scopeIds; }
    public void setScopeIds(String scopeIds) { this.scopeIds = scopeIds; }
    public Integer getStackingLayer() { return stackingLayer; }
    public void setStackingLayer(Integer stackingLayer) { this.stackingLayer = stackingLayer; }
    public String getExclusionGroup() { return exclusionGroup; }
    public void setExclusionGroup(String exclusionGroup) { this.exclusionGroup = exclusionGroup; }
    public Integer getFunderType() { return funderType; }
    public void setFunderType(Integer funderType) { this.funderType = funderType; }
    public Integer getBudgetRatio() { return budgetRatio; }
    public void setBudgetRatio(Integer budgetRatio) { this.budgetRatio = budgetRatio; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
