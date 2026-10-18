package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponConfirmExceptionPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 核销异常 Mapper（V23）：usage_no 唯一，人工处理结果留痕。 */
@Mapper
public interface CouponConfirmExceptionMapper extends BaseMapper<CouponConfirmExceptionPO> {

    @Select("SELECT * FROM coupon_confirm_exception WHERE usage_no = #{usageNo}")
    CouponConfirmExceptionPO selectByUsageNo(@Param("usageNo") String usageNo);

    @Select("SELECT * FROM coupon_confirm_exception WHERE resolve_status = 'PENDING' "
            + "ORDER BY create_time DESC LIMIT #{limit}")
    List<CouponConfirmExceptionPO> selectPending(@Param("limit") int limit);

    @Update("UPDATE coupon_confirm_exception SET resolve_status = #{status}, "
            + "resolve_reason = #{reason}, resolve_time = #{time} "
            + "WHERE usage_no = #{usageNo} AND resolve_status = 'PENDING'")
    int markResolved(@Param("usageNo") String usageNo,
                     @Param("status") String status,
                     @Param("reason") String reason,
                     @Param("time") LocalDateTime time);
}
