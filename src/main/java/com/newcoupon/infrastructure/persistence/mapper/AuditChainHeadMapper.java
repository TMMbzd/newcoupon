package com.newcoupon.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 审计链头（ADR-0021 Phase 1）：单行 FOR UPDATE，把“读最后 hash + 插记录 + 更新链头”
 * 串行化到数据库，替代只在本 JVM 有效的 synchronized。
 */
@Mapper
public interface AuditChainHeadMapper {

    @Select("SELECT last_hash FROM audit_chain_head WHERE id = 1 FOR UPDATE")
    String selectLastHashForUpdate();

    @Update("UPDATE audit_chain_head SET last_hash = #{hash} WHERE id = 1")
    int updateLastHash(@Param("hash") String hash);
}
