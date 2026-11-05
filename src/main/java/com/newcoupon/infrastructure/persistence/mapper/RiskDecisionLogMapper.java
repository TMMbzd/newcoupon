package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.RiskDecisionLogPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 风控决策日志 Mapper（V48，V6 4A）。查询走 MyBatis-Plus QueryWrapper
 * （分页过滤维度多，见 RiskDecisionQueryService）；本接口只承担插入与主键查询。
 */
@Mapper
public interface RiskDecisionLogMapper extends BaseMapper<RiskDecisionLogPO> {
}
