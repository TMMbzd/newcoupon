package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 模板 Mapper。继承 BaseMapper 后白送一套单表 CRUD（insert/selectById/...），
 * 只有通用能力覆盖不了的操作才手写——本项目的原则：
 * <b>涉及状态迁移/并发控制的写操作，必须手写带条件的 CAS SQL，禁止"读出来改完整个 updateById"</b>。
 */
@Mapper
public interface TemplateMapper extends BaseMapper<CouponTemplatePO> {

    /**
     * 发布状态条件迁移（CAS）：
     * UPDATE ... SET publish_status = to WHERE id=? AND publish_status = from
     * 影响行数=0 → 状态已被并发变更（或 from 不匹配），调用方按状态冲突处理。
     * 这一行 SQL 就是"数据库层的乐观锁"——两个并发发布请求，只有一个能成功。
     */
    @Update("UPDATE coupon_template SET publish_status = #{to} "
            + "WHERE id = #{id} AND publish_status = #{from}")
    int casUpdatePublishStatus(@Param("id") long id,
                               @Param("from") int from,
                               @Param("to") int to);

    /**
     * 条件扣库存（ADR-0004 增补1）：守卫=库存未售罄 + 已发布 + 已审批。
     * "检查+写入"在一条 SQL 内原子完成，check-then-act 竞态无从发生。
     * 影响行数=0 → 调用方抛 StockDeductRejectedException 使事务回滚，由编排层在【事务外】
     * 用新快照分类（RR 快照教训：事务内重读看到旧照片，会误报——见该异常类的完整注释）。
     */
    @Update("UPDATE coupon_template SET issued_count = issued_count + 1 "
            + "WHERE id = #{id} AND issued_count < total_count "
            + "AND publish_status = 1 AND approve_status = 1")
    int casDeductStock(@Param("id") long id);

    /** 已发布模板清单：库存对账定时任务用 */
    @Select("SELECT * FROM coupon_template WHERE publish_status = 1")
    java.util.List<CouponTemplatePO> selectPublished();

    /** 某接入方名下的模板 id 清单（V6 2B：租户数据导出的模板清单，按 id 升序保证导出可复现） */
    @Select("SELECT id FROM coupon_template WHERE app_id = #{appId} ORDER BY id")
    java.util.List<Long> selectIdsByApp(@Param("appId") String appId);

    /**
     * 行锁读：reshard/rollback 事务先锁模板行，再锁分片行——与库存调整的
     * “模板 CAS → 分片 UPDATE”保持同一加锁顺序，避免 AB-BA 死锁。
     */
    @Select("SELECT * FROM coupon_template WHERE id = #{id} FOR UPDATE")
    CouponTemplatePO selectByIdForUpdate(@Param("id") long id);

    /**
     * C1 库存调整：按“预期总量”做乐观并发更新。
     * 影响 0 行 = 总量已被并发调整；调用方按状态冲突处理。
     */
    @Update("UPDATE coupon_template SET total_count = #{target}, version = version + 1 "
            + "WHERE id = #{id} AND total_count = #{expected}")
    int casUpdateTotalCount(@Param("id") long id,
                            @Param("expected") long expected,
                            @Param("target") long target);

    /** 发布建布局后把配置片数改写成实际 N_eff，领券路由直接用它（避免 total 很小时探测空片）。 */
    @Update("UPDATE coupon_template SET stock_shard_count = #{n} "
            + "WHERE id = #{id}")
    int updateActualShardCount(@Param("id") long id, @Param("n") int n);

    /**
     * 布局版本与片数的原子切换（reshard 正向 / rollback 反向共用）。
     *
     * <p>{@code WHERE stock_layout_version=from AND total_count=expectedTotal} 是并发闸门：
     * 并发的 reshard 或库存调整只要先提交，本语句就 0 行，调用方整体回滚。
     * 0 行 = 状态已被并发变更，不盲写。
     */
    @Update("UPDATE coupon_template SET stock_layout_version = #{toVersion}, "
            + "stock_shard_count = #{shardCount}, version = version + 1 "
            + "WHERE id = #{id} AND stock_layout_version = #{fromVersion} "
            + "AND total_count = #{expectedTotal}")
    int casBumpLayoutVersion(@Param("id") long id,
                             @Param("fromVersion") int fromVersion,
                             @Param("toVersion") int toVersion,
                             @Param("shardCount") int shardCount,
                             @Param("expectedTotal") long expectedTotal);
}
