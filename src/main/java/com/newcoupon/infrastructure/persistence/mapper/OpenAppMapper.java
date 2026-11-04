package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 接入方 Mapper（S1）：通用 CRUD 由 BaseMapper 提供，条件查询在仓储实现里组装 */
@Mapper
public interface OpenAppMapper extends BaseMapper<OpenAppPO> {

    /**
     * 行锁读（V6 2B）：生命周期迁移事务先锁住应用行再查迁移表——
     * 两个运营并发对同一应用做不同迁移时，后拿锁的能看到先提交的状态；
     * 数据导出的一次性闸门也靠这把锁串行化（见 TenantLifecycleTxService）。
     */
    @Select("SELECT * FROM open_app WHERE app_id = #{appId} FOR UPDATE")
    OpenAppPO selectByAppIdForUpdate(@Param("appId") String appId);
}
