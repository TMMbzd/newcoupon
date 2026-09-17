package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponActivityPO;
import org.apache.ibatis.annotations.Mapper;

/** 营销活动 Mapper（小配置表不分片，单表 CRUD） */
@Mapper
public interface CouponActivityMapper extends BaseMapper<CouponActivityPO> {
}
