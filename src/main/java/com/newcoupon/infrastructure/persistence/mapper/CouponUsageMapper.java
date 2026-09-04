package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponUsagePO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 占用单 Mapper。两条状态迁移 CAS：
 * 核销（0→1，同时写金额与时间）/ 释放（0→2）——影响行数=0 即并发冲突或状态不满足。
 */
@Mapper
public interface CouponUsageMapper extends BaseMapper<CouponUsagePO> {

    @Select("SELECT * FROM coupon_usage WHERE usage_no = #{usageNo}")
    CouponUsagePO selectByUsageNo(@Param("usageNo") String usageNo);

    @Update("UPDATE coupon_usage SET status = 1, discount_amt = #{discountAmt}, use_time = #{useTime} "
            + "WHERE usage_no = #{usageNo} AND status = 0")
    int casConfirm(@Param("usageNo") String usageNo,
                   @Param("discountAmt") long discountAmt,
                   @Param("useTime") LocalDateTime useTime);

    @Update("UPDATE coupon_usage SET status = 2, release_time = #{releaseTime} "
            + "WHERE usage_no = #{usageNo} AND status = 0")
    int casRelease(@Param("usageNo") String usageNo,
                   @Param("releaseTime") LocalDateTime releaseTime);
}
