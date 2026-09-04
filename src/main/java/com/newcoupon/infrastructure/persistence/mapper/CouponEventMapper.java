package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponEventPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 券事件 Mapper：只有 insert（append-only）。重放/补偿产生的重复事件
 * 撞 uk_event_no 唯一索引抛 DuplicateKeyException——由所在事务整体回滚（事件不可被污染）。
 */
@Mapper
public interface CouponEventMapper extends BaseMapper<CouponEventPO> {
}
