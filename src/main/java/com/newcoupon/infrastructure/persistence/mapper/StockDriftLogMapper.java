package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.StockDriftLogPO;
import org.apache.ibatis.annotations.Mapper;

/** 漂移日志 Mapper：只插入 + 按模板查最近记录（连续漂移升级判断用） */
@Mapper
public interface StockDriftLogMapper extends BaseMapper<StockDriftLogPO> {
}
