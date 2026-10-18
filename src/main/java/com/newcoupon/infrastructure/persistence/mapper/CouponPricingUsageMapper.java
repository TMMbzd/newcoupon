package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponPricingUsagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 占用单 ↔ 价格快照绑定 Mapper（V23）。 */
@Mapper
public interface CouponPricingUsageMapper extends BaseMapper<CouponPricingUsagePO> {

    @Select("SELECT * FROM coupon_pricing_usage WHERE usage_no = #{usageNo}")
    CouponPricingUsagePO selectByUsageNo(@Param("usageNo") String usageNo);
}
