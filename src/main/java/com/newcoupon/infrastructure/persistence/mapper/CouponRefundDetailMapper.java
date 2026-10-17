package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponRefundDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 退款明细 Mapper（V21）：按占用单/退款单查已退数量与金额，用于累计不超原数量的守卫。 */
@Mapper
public interface CouponRefundDetailMapper extends BaseMapper<CouponRefundDetailPO> {

    @Select("SELECT * FROM coupon_refund_detail WHERE usage_no = #{usageNo} ORDER BY id")
    List<CouponRefundDetailPO> selectByUsageNo(@Param("usageNo") String usageNo);

    @Select("SELECT * FROM coupon_refund_detail WHERE refund_no = #{refundNo} ORDER BY id")
    List<CouponRefundDetailPO> selectByRefundNo(@Param("refundNo") String refundNo);
}
