package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppPlanApplyPO;
import org.apache.ibatis.annotations.Mapper;

/** 预案套用记录（V16，S2 第 10 步） */
@Mapper
public interface OpenAppPlanApplyMapper extends BaseMapper<OpenAppPlanApplyPO> {
}
