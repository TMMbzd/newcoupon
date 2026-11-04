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

    /**
     * 无锁读链头（verify 用）：FOR UPDATE 会与写路径互卡——校验是只读巡检，
     * 不该挡写入。与末次写入存在毫秒级竞窗（写事务先 insert 后 update head），
     * 交错读最多看到"head 落后一行"，消费方（AuditVerifyService）注释已写明复跑定性。
     */
    @Select("SELECT last_hash FROM audit_chain_head WHERE id = 1")
    String selectLastHash();

    @Update("UPDATE audit_chain_head SET last_hash = #{hash} WHERE id = 1")
    int updateLastHash(@Param("hash") String hash);
}
