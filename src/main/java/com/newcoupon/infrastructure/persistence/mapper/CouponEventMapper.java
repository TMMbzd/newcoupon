package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponEventPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 券事件 Mapper：只有 insert（append-only）。重放/补偿产生的重复事件
 * 撞 uk_event_no 唯一索引抛 DuplicateKeyException——由所在事务整体回滚（事件不可被污染）。
 */
@Mapper
public interface CouponEventMapper extends BaseMapper<CouponEventPO> {

    /** C1 批次日限：template_id + occur_time 正好走 idx_tpl_time；event_type=1 是发放事件。 */
    @Select("SELECT COUNT(*) FROM coupon_event "
            + "WHERE event_type = 1 AND template_id = #{templateId} AND occur_time >= #{since}")
    long countIssuedByTemplateSince(@Param("templateId") long templateId,
                                    @Param("since") java.time.LocalDateTime since);

}
