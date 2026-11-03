package com.newcoupon.infrastructure.security;

import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import com.newcoupon.infrastructure.persistence.po.AdminUserPO;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * RBAC 数据装配（V6 2A）：admin_user / admin_role / admin_permission 五表的读模型。
 *
 * <p>这层不做任何判断（有没有权限是 Spring Security 的事），只负责把 DB 行变成
 * 登录、会话续验、/me 展示需要的形状。
 */
@Service
public class AdminRbacService {

    private final AdminRbacMapper mapper;

    public AdminRbacService(AdminRbacMapper mapper) {
        this.mapper = mapper;
    }

    /** 登录第一步：按用户名取用户。返回 null = 用户不存在（调用方统一按"用户名或密码错误"处理）。 */
    public AdminUserPO findUser(String username) {
        return mapper.findByUsername(username);
    }

    /** 会话续验：按 userId 取用户（核对 enabled/auth_version）。 */
    public AdminUserPO findUserById(long userId) {
        return mapper.findById(userId);
    }

    /** 用户角色码（展示/审计用；授权不按角色码判断）。 */
    public List<String> roleCodes(long userId) {
        return mapper.findRoleCodes(userId);
    }

    /** 用户有效权限码 = hasAuthority 的判断数据源。 */
    public List<String> permissionCodes(long userId) {
        return mapper.findPermissionCodes(userId);
    }

    /** 全量权限码字典：旧管理令牌持有者的权限集（= PLATFORM_ADMIN 全量）。 */
    public List<String> allPermissionCodes() {
        return mapper.findAllPermissionCodes();
    }
}
