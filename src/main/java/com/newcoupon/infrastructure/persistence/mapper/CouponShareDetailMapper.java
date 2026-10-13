package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponShareDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 成本分摊明细 Mapper（P5 核销事务写入；守恒校验 SUM(share_total)=discount_amt） */
@Mapper
public interface CouponShareDetailMapper extends BaseMapper<CouponShareDetailPO> {

    /** 按占用单号汇总分摊总额（守恒校验用） */
    @Select("SELECT COALESCE(SUM(share_total), 0) FROM coupon_share_detail WHERE usage_no = #{usageNo}")
    long sumShareByUsageNo(@Param("usageNo") String usageNo);

    /** 按占用单号取正分账行（退款冲销的镜像来源） */
    @Select("SELECT * FROM coupon_share_detail WHERE usage_no = #{usageNo} AND share_total > 0")
    List<CouponShareDetailPO> selectPositiveByUsageNo(@Param("usageNo") String usageNo);
}
