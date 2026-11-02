package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponStockAdjustLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 库存调整审计 Mapper（V26）。 */
@Mapper
public interface CouponStockAdjustLogMapper extends BaseMapper<CouponStockAdjustLogPO> {

    @Select("SELECT * FROM coupon_stock_adjust_log "
            + "WHERE template_id = #{templateId} AND request_id = #{requestId}")
    CouponStockAdjustLogPO selectByRequest(@Param("templateId") long templateId,
                                           @Param("requestId") String requestId);
}
