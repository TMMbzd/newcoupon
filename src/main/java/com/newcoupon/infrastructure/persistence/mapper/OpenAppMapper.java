package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppPO;
import org.apache.ibatis.annotations.Mapper;

/** 接入方 Mapper（S1）：通用 CRUD 由 BaseMapper 提供，条件查询在仓储实现里组装 */
@Mapper
public interface OpenAppMapper extends BaseMapper<OpenAppPO> {
}
