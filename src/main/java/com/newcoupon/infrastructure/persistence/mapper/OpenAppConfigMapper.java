package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppConfigPO;
import org.apache.ibatis.annotations.Mapper;

/** 租户策略配置 Mapper（S2） */
@Mapper
public interface OpenAppConfigMapper extends BaseMapper<OpenAppConfigPO> {
}
