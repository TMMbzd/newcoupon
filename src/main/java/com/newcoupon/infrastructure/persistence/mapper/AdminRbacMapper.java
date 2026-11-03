package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.AdminUserPO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 管理面 RBAC Mapper（V44，V6 2A）——全部未分片小表，等值/主键查询。
 *
 * <p>权限加载查询的调用频率契约：每个**已登录**的管理面请求一次（AdminAuthorityRefreshFilter
 * 每请求重载权限）+ 每次登录各一次。管理面是低频面（人肉操作，QPS 个位数），
 * 不做缓存——换来的性质是"改角色绑定/停用用户，下一个请求即生效"（设计裁定，防缓存失效遗漏）。
 */
@Mapper
public interface AdminRbacMapper extends BaseMapper<AdminUserPO> {

    /** 按登录名查用户（登录入口；uk_username 等值命中）。不存在返回 null，由调用方统一"用户名或密码错误"。 */
    @Select("SELECT * FROM admin_user WHERE username = #{username}")
    AdminUserPO findByUsername(@Param("username") String username);

    /** 会话续验用：只按主键取行（enabled/auth_version 每请求核对，见 AdminAuthorityRefreshFilter）。 */
    @Select("SELECT * FROM admin_user WHERE id = #{userId}")
    AdminUserPO findById(@Param("userId") long userId);

    /** 用户持有的角色码（/me 展示与审计用；授权判断走权限码，不用角色码）。 */
    @Select("SELECT r.code FROM admin_role r "
            + "JOIN admin_user_role ur ON ur.role_id = r.id "
            + "WHERE ur.user_id = #{userId} ORDER BY r.code")
    List<String> findRoleCodes(@Param("userId") long userId);

    /** 用户的有效权限码：user_role × role_permission 两跳 join（即授权判断的数据源）。 */
    @Select("SELECT DISTINCT p.code FROM admin_permission p "
            + "JOIN admin_role_permission rp ON rp.permission_id = p.id "
            + "JOIN admin_user_role ur ON ur.role_id = rp.role_id "
            + "WHERE ur.user_id = #{userId} ORDER BY p.code")
    List<String> findPermissionCodes(@Param("userId") long userId);

    /**
     * 全量权限码字典：旧管理令牌（X-Admin-Token）认证成功后按"平台管理员全量权限"授予权限。
     * 查字典而不是硬编码——2A-2 继续加 @PreAuthorize 时旧令牌自动获得新权限码，不用改这行。
     */
    @Select("SELECT code FROM admin_permission ORDER BY code")
    List<String> findAllPermissionCodes();

    /** 停用/启用用户（测试与管理动作用；停用后该用户全部会话下一请求 401）。 */
    @Update("UPDATE admin_user SET enabled = #{enabled} WHERE id = #{userId}")
    int updateEnabled(@Param("userId") long userId, @Param("enabled") int enabled);

    /** auth_version +1：让该用户全部存量会话的版本比对失效（改密/强制重登的信号）。 */
    @Update("UPDATE admin_user SET auth_version = auth_version + 1 WHERE id = #{userId}")
    int bumpAuthVersion(@Param("userId") long userId);

    /** 按 role_code 绑用户（种子初始化用：admin → PLATFORM_ADMIN）。IGNORE=幂等，重绑不炸。 */
    @Insert("INSERT IGNORE INTO admin_user_role (user_id, role_id) "
            + "SELECT #{userId}, id FROM admin_role WHERE code = #{roleCode}")
    int bindRole(@Param("userId") long userId, @Param("roleCode") String roleCode);

    /** 按 role_code 摘角色（权限即时生效的入口：摘完下一请求就没有该角色权限）。 */
    @Delete("DELETE ur FROM admin_user_role ur "
            + "JOIN admin_role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId} AND r.code = #{roleCode}")
    int deleteRoleBinding(@Param("userId") long userId, @Param("roleCode") String roleCode);
}
