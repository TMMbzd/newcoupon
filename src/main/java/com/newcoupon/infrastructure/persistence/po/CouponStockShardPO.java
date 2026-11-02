package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * C1 库存分片行（V25，ADR-0018；V37 起按 layout_version 版本化）。
 *
 * <p>同一模板的**每个布局版本**各有 N_eff 行，只有
 * {@code coupon_template.stock_layout_version} 指向的版本可扣减；退役版本保留
 * {@code status=1、quota=used} 只读。全局 Σquota=total_count 跨所有版本成立。
 *
 * <p>它不是缓存：每次领券事务直接对 {@code used} 做条件更新，{@code used<quota} 是零超发的
 * 数据库承重墙；Redis 不再保存库存。{@code reserved} 为 Phase 2 TCC 预留，Phase 1 恒 0。
 */
@TableName("coupon_stock_shard")
public class CouponStockShardPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    /** V37：布局版本；只有 coupon_template.stock_layout_version 指向的版本可扣减。 */
    private Integer layoutVersion;
    private Integer shardNo;
    private Long quota;
    private Long used;
    /** V37：Phase 2 TCC 预占；Phase 1 恒 0。 */
    private Long reserved;
    private Integer status;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getLayoutVersion() { return layoutVersion; }
    public void setLayoutVersion(Integer layoutVersion) { this.layoutVersion = layoutVersion; }
    public Integer getShardNo() { return shardNo; }
    public void setShardNo(Integer shardNo) { this.shardNo = shardNo; }
    public Long getQuota() { return quota; }
    public void setQuota(Long quota) { this.quota = quota; }
    public Long getUsed() { return used; }
    public void setUsed(Long used) { this.used = used; }
    public Long getReserved() { return reserved; }
    public void setReserved(Long reserved) { this.reserved = reserved; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
