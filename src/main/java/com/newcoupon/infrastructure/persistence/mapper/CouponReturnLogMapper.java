package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponReturnLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 退券留痕 Mapper（V21）：一券限退 N 次的计数来源。 */
@Mapper
public interface CouponReturnLogMapper extends BaseMapper<CouponReturnLogPO> {

    @Select("SELECT COUNT(*) FROM coupon_return_log WHERE user_coupon_id = #{userCouponId}")
    int countByCoupon(@Param("userCouponId") long userCouponId);
}
