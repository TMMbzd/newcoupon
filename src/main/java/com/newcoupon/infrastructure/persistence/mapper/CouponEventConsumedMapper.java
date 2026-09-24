package com.newcoupon.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消费幂等锚点 Mapper（V12）。insertIgnore 的返回行数就是判据：1=首次，0=重投。
 */
@Mapper
public interface CouponEventConsumedMapper {

    @Insert("INSERT IGNORE INTO coupon_event_consumed (event_no, topic) VALUES (#{eventNo}, #{topic})")
    int insertIgnore(@Param("eventNo") String eventNo, @Param("topic") String topic);
}
