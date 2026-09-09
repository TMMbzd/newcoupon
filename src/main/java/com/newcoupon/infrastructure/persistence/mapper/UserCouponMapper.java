package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.UserCouponPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户券 Mapper。幂等键查询手写 SQL（走 uk_idem 覆盖索引）；
 * 插入用 BaseMapper 的 insert——撞 uk_idem（幂等）/ uk_user_seq（限领N兜底，V2 迁移）
 * 抛 DuplicateKeyException，由编排层事务外重查分类（ADR-0006）。
 * 如需按用户+模板查多行（限领N下同组合有多行），走 uk_user_seq 最左前缀、返回 List。
 */
@Mapper
public interface UserCouponMapper extends BaseMapper<UserCouponPO> {

    /** 幂等键查询（唯一索引 uk_idem 覆盖，等值双条件） */
    @Select("SELECT * FROM user_coupon WHERE user_id = #{userId} AND idempotent_key = #{idempotentKey}")
    UserCouponPO selectByIdempotentKey(@Param("userId") long userId,
                                       @Param("idempotentKey") String idempotentKey);

    /** 券包列表（status 为 null 查全部），走 idx_user_status 覆盖 */
    @Select("SELECT * FROM user_coupon WHERE user_id = #{userId} "
            + "AND (#{status} IS NULL OR status = #{status}) ORDER BY id DESC")
    java.util.List<UserCouponPO> selectByUser(@Param("userId") long userId,
                                              @Param("status") Integer status);

    /** 按模板数券（ADR-0008 第二不变式）：issued_count ↔ COUNT(user_coupon) 互证，修复守卫用 */
    @Select("SELECT COUNT(*) FROM user_coupon WHERE template_id = #{templateId}")
    long countByTemplate(@Param("templateId") long templateId);

    // ---------- 状态迁移 CAS（每条 = 状态机一条合法边） ----------

    /** 锁券：待使用→已锁定；条件含未过期（过期的券锁了也用不了） */
    @Update("UPDATE user_coupon SET status = 1 WHERE id = #{id} AND status = 0 AND valid_end > NOW()")
    int casLock(@Param("id") long id);

    /** 核销：已锁定→已核销（终态） */
    @Update("UPDATE user_coupon SET status = 2 WHERE id = #{id} AND status = 1")
    int casConfirm(@Param("id") long id);

    /** 释放：已锁定→未过期?待使用:已过期（过期券不能退回可用态） */
    @Update("UPDATE user_coupon SET status = IF(valid_end > NOW(), 0, 3) WHERE id = #{id} AND status = 1")
    int casRelease(@Param("id") long id);
}
