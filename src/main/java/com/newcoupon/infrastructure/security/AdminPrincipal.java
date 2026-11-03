package com.newcoupon.infrastructure.security;

import java.io.Serializable;

/**
 * 管理面会话里的身份主体（V6 2A）——**只有身份，没有权限**。
 *
 * <p>为什么权限不放进会话（设计裁定）：权限进会话 = 改角色绑定后要等人重登才生效，
 * 还得维护"改权限 → 清缓存/踢会话"的联动。只存身份、每请求从 DB 重载权限，
 * 代价是管理面每个已认证请求一次 join 查询（低频面，可接受），换来的是
 * "改权限/停用立即生效"这个性质天然成立。
 *
 * <p>Serializable：会话属性要经 JDK 序列化进 Redis（RedisIndexedSessionRepository）。
 */
public class AdminPrincipal implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long userId;
    private final String username;

    public AdminPrincipal(long userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    /** Authentication.getName() 走这里——也是 Redis 会话按用户名建索引的键（强制下线靠它反查会话）。 */
    @Override
    public String toString() {
        return username;
    }
}
