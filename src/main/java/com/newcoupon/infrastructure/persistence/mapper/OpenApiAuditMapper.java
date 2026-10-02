package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.OpenApiAuditPO;
import org.apache.ibatis.annotations.Mapper;

/** 开放接口审计 Mapper（S1）：只插入（审计行是事实，不做更新） */
@Mapper
public interface OpenApiAuditMapper extends BaseMapper<OpenApiAuditPO> {
}
