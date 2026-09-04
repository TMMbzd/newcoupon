package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.UserCouponPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户券 Mapper。两个唯一键查询手写 SQL（走覆盖索引，语义清晰）；
 * 插入用 BaseMapper 的 insert——插入时撞任一唯一索引会抛 DuplicateKeyException，
 * 由编排层回滚后重查分类（ADR-0004 增补3），不在 SQL 层做任何"先查后插"。
 */
@Mapper
public interface UserCouponMapper extends BaseMapper<UserCouponPO> {

    /** 幂等键查询（唯一索引 uk_idem 覆盖，等值双条件） */
    @Select("SELECT * FROM user_coupon WHERE user_id = #{userId} AND idempotent_key = #{idempotentKey}")
    UserCouponPO selectByIdempotentKey(@Param("userId") long userId,
                                       @Param("idempotentKey") String idempotentKey);

    /** 用户+模板查询（唯一索引 uk_user_template 覆盖） */
    @Select("SELECT * FROM user_coupon WHERE user_id = #{userId} AND template_id = #{templateId}")
    UserCouponPO selectByUserAndTemplate(@Param("userId") long userId,
                                         @Param("templateId") long templateId);
}
