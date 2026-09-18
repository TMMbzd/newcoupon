package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 库存分桶账本 PO（V10 P2b，A 簇裁决——超发红线的 DB 承重墙）。
 * 一行 = 一个模板×桶号×世代的分桶账：quota 由开桶 CAS 分配，
 * used 由领券事务内逐笔条件扣推进（WHERE used&lt;quota）。
 * 单调不变量：template.allocated ≡ SUM(quota)；E3c 镜像锚：Σdbi ↔ SUM(used)。
 * 模板维度配置数据，不分片（V6 决策同 coupon_template）。
 */
@TableName("stock_bucket")
public class StockBucketPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private Integer bucketNo;
    private Integer generation;
    private Long quota;
    private Long used;
    private Integer status;       // 0活跃 1封账待GC 2已归档
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getBucketNo() { return bucketNo; }
    public void setBucketNo(Integer bucketNo) { this.bucketNo = bucketNo; }
    public Integer getGeneration() { return generation; }
    public void setGeneration(Integer generation) { this.generation = generation; }
    public Long getQuota() { return quota; }
    public void setQuota(Long quota) { this.quota = quota; }
    public Long getUsed() { return used; }
    public void setUsed(Long used) { this.used = used; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
