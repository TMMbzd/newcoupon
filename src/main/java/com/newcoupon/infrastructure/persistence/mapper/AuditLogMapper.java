package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计日志 Mapper（第 1 环）。
 *
 * <p>只有 INSERT 和 SELECT——审计日志只写不改不删（应用层保证）。
 * MyBatis-Plus 的 BaseMapper 自带 insert/selectById/selectList，
 * 不使用其 update/delete 方法（代码中无调用路径）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogPO> {
}
