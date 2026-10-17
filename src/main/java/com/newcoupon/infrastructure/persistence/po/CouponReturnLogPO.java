package com.newcoupon.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 整单退款退券留痕（V21）：同一 refund_no 只记一次，用于“一券限退 N 次”与审计。 */
@TableName("coupon_return_log")
public class CouponReturnLogPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userCouponId;
    private String refundNo;
    private LocalDateTime returnTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserCouponId() { return userCouponId; }
    public void setUserCouponId(Long userCouponId) { this.userCouponId = userCouponId; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public LocalDateTime getReturnTime() { return returnTime; }
    public void setReturnTime(LocalDateTime returnTime) { this.returnTime = returnTime; }
}
