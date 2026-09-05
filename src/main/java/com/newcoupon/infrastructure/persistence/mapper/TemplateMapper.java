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
     * 影响行数=0 → 调用方在同一事务内重读模板分类 STOCK_OUT / TEMPLATE_NOT_RECEIVABLE。
     */
    @Update("UPDATE coupon_template SET issued_count = issued_count + 1 "
            + "WHERE id = #{id} AND issued_count < total_count "
            + "AND publish_status = 1 AND approve_status = 1")
    int casDeductStock(@Param("id") long id);

    /** 已发布模板清单：库存对账定时任务用 */
    @Select("SELECT * FROM coupon_template WHERE publish_status = 1")
    java.util.List<CouponTemplatePO> selectPublished();
}
