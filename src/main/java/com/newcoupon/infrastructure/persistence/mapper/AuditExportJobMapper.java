package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.AuditExportJobPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 审计导出任务 Mapper（V6 2C）。
 *
 * <p>状态迁移用**条件更新**（CAS）而不是读-改-写：下载的一次性语义靠
 * {@code WHERE downloaded_at IS NULL} 保证——两个管理员同时点下载，只有一个
 * UPDATE 打得中，另一个收"已下载过"，不会出现双份出域。
 */
@Mapper
public interface AuditExportJobMapper extends BaseMapper<AuditExportJobPO> {

    /** 一次性下载的 CAS：仅当尚未下载过时落 downloaded_at；返回 0=已被下载/行不存在。 */
    @Update("UPDATE audit_export_job SET downloaded_at = #{at} "
            + "WHERE job_no = #{jobNo} AND downloaded_at IS NULL")
    int markDownloaded(@Param("jobNo") String jobNo, @Param("at") java.time.LocalDateTime at);
}
