package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppConfigLogPO;
import org.apache.ibatis.annotations.Mapper;

/** 租户配置变更审计 Mapper（S2）：只插入 */
@Mapper
public interface OpenAppConfigLogMapper extends BaseMapper<OpenAppConfigLogPO> {
}
