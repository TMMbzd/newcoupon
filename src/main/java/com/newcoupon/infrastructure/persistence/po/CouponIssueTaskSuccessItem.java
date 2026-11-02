package com.newcoupon.infrastructure.persistence.po;

/**
 * 批量成功回写参数：一条任务 id 对应它刚发出的券 id。
 * 用 CASE 一次更新整组任务，避免 125 条任务再产生 125 条 UPDATE。
 */
public class CouponIssueTaskSuccessItem {

    private Long id;
    private Long couponId;

    public CouponIssueTaskSuccessItem() {
    }

    public CouponIssueTaskSuccessItem(Long id, Long couponId) {
        this.id = id;
        this.couponId = couponId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }
}
