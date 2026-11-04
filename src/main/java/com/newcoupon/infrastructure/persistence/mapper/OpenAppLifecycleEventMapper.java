package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppLifecycleEventPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 生命周期事件 Mapper（V6 2B）。插入走 BaseMapper；
 * {@link #existsByAppAndAction} 是数据导出"一次性"闸门的判据——
 * 与插入发生在同一事务、且事务先持有应用行锁（见 TenantLifecycleTxService），
 * 两个并发的首次导出会被行锁串行化，输家必然看得到赢家的事件行。
 */
@Mapper
public interface OpenAppLifecycleEventMapper extends BaseMapper<OpenAppLifecycleEventPO> {

    /** 指定动作是否发生过（目前只有 data_export 用——"一次性"的判据） */
    @Select("SELECT COUNT(*) FROM open_app_lifecycle_event "
            + "WHERE app_id = #{appId} AND action = #{action}")
    boolean existsByAppAndAction(@Param("appId") String appId, @Param("action") String action);

    /** 按时间序取全部事件（运营回放/排障用；量级=迁移次数，不分页） */
    @Select("SELECT * FROM open_app_lifecycle_event WHERE app_id = #{appId} ORDER BY id")
    List<OpenAppLifecycleEventPO> selectByApp(@Param("appId") String appId);
}
