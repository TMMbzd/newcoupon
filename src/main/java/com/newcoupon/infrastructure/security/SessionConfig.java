package com.newcoupon.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * 管理面会话装配（V6 2A）：Redis 会话 + 按用户名索引。
 *
 * <p><b>选 RedisIndexedSessionRepository 而不是默认 RedisSessionRepository</b>：
 * "强制下线"要按用户名反查该用户的全部会话（findByPrincipalName）——索引仓才支持；
 * 索引键在会话写入 SPRING_SECURITY_CONTEXT 后自动维护（principal 名 = Authentication.getName()
 * = 登录名），不需要手工登记。
 *
 * <p><b>命名空间 {@code c:session}}</b>：业务键全部以 {@code c:} 分域（c:open:nonce:*、
 * c:cache:*、c:admin:*），会话同规矩——否则 keyspace 扫描/清理脚本会把会话键和业务键混在一起。
 *
 * <p>空闲超时（默认 30 分钟）在登录时按用户会话写入（AdminSessionService，
 * {@code session.setMaxInactiveInterval}），可配置；绝对时长上限（默认 8 小时）由
 * AdminAuthorityRefreshFilter 核对（Spring Session 只有空闲语义，绝对上限是会话治理要求）。
 */
@Configuration
@EnableRedisIndexedHttpSession(redisNamespace = "c:session", maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {

    /**
     * 会话注册表：Spring Security 的会话治理视角（列出某用户的全部会话）。
     * 本刀用于强制下线；并发登录控制（同账号最多 N 会话）2A-2 接入时直接可用。
     */
    @Bean
    public SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry(
            RedisIndexedSessionRepository sessionRepository) {
        return new SpringSessionBackedSessionRegistry<>(sessionRepository);
    }
}
