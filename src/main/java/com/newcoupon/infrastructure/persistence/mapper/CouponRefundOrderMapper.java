package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponRefundOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 退款单 Mapper（V21）：refund_no 唯一 = 幂等锚点。 */
@Mapper
public interface CouponRefundOrderMapper extends BaseMapper<CouponRefundOrderPO> {

    @Select("SELECT * FROM coupon_refund_order WHERE refund_no = #{refundNo}")
    CouponRefundOrderPO selectByRefundNo(@Param("refundNo") String refundNo);

    /** 同一占用单已发生的退款次数（部分退款次数上限） */
    @Select("SELECT COUNT(*) FROM coupon_refund_order WHERE usage_no = #{usageNo}")
    int countByUsageNo(@Param("usageNo") String usageNo);
}
