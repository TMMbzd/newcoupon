package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 占用单 PO，对应表 coupon_usage（uk_usage_no 唯一 = 交易链路幂等锚点） */
@TableName("coupon_usage")
public class CouponUsagePO {

    @TableId(type = IdType.INPUT)   // 雪花ID显式赋值（ADR-0010批次二）
    private Long id;

    private String usageNo;
    private Long userId;
    private Long userCouponId;
    private String orderNo;
    private Integer status;          // 0占用中 1已核销 2已释放
    private Long discountAmt;
    private LocalDateTime occupyTime;
    private LocalDateTime useTime;
    private LocalDateTime releaseTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsageNo() { return usageNo; }
    public void setUsageNo(String usageNo) { this.usageNo = usageNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getUserCouponId() { return userCouponId; }
    public void setUserCouponId(Long userCouponId) { this.userCouponId = userCouponId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getDiscountAmt() { return discountAmt; }
    public void setDiscountAmt(Long discountAmt) { this.discountAmt = discountAmt; }
    public LocalDateTime getOccupyTime() { return occupyTime; }
    public void setOccupyTime(LocalDateTime occupyTime) { this.occupyTime = occupyTime; }
    public LocalDateTime getUseTime() { return useTime; }
    public void setUseTime(LocalDateTime useTime) { this.useTime = useTime; }
    public LocalDateTime getReleaseTime() { return releaseTime; }
    public void setReleaseTime(LocalDateTime releaseTime) { this.releaseTime = releaseTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
