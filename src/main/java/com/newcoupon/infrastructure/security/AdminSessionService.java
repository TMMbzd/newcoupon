package com.newcoupon.infrastructure.security;

import com.newcoupon.application.service.AuditService;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import com.newcoupon.infrastructure.persistence.po.AdminUserPO;
import com.newcoupon.infrastructure.web.RiskControlFilter;
import com.newcoupon.infrastructure.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.stereotype.Service;

/**
 * 管理面会话服务（V6 2A）：登录（锁定计数 + 密码校验 + 会话建立/轮换）、登出、当前用户、强制下线。
 *
 * <p><b>登录失败的对外纪律</b>：用户不存在 / 密码错 / 锁定期，一律同一句"用户名或密码错误"
 * （7011）——区分开任何一种，就是把"这个用户名存在"泄露给探测者。真实原因只进审计。
 *
 * <p><b>为什么登录不走 AuthenticationManager/formLogin**：Spring Security 的表单登录面向
 * 浏览器重定向流（302/登录页），而控制台是 JSON API；自己校验密码 + 建会话，行为每一步可见，
 * 锁定计数/审计/统一文案这些定制点也不用跟 filter 套装搏斗。密码比对仍用统一的
 * {@link PasswordEncoder}（DelegatingPasswordEncoder + BCrypt(12)）。
 */
@Service
public class AdminSessionService {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionService.class);

    /** 登录失败计数键前缀（Redis）：值 = 窗口内失败次数，TTL = 锁定窗口。 */
    static final String FAIL_KEY_PREFIX = "c:admin:auth:fail:";

    /** 审计动作族（2C 会把 IAM 动作并入统一矩阵）。 */
    public static final String AUDIT_LOGIN_SUCCESS = "ADMIN_AUTH_LOGIN_SUCCESS";
    public static final String AUDIT_LOGIN_FAILED = "ADMIN_AUTH_LOGIN_FAILED";
    public static final String AUDIT_LOCKED = "ADMIN_AUTH_LOCKED";
    public static final String AUDIT_LOGOUT = "ADMIN_AUTH_LOGOUT";
    public static final String AUDIT_FORCE_LOGOUT = "ADMIN_AUTH_FORCE_LOGOUT";

    private final AdminRbacMapper rbacMapper;
    private final AdminRbacService rbacService;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final com.newcoupon.infrastructure.audit.AuditActorResolver actorResolver;
    private final SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry;
    private final RedisIndexedSessionRepository sessionRepository;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    /** 未知用户名也跑一次真实 BCrypt 比对：让"用户不存在"与"密码错"耗时一致，堵时序侧信道。 */
    private final String dummyHash;

    private final int loginIpLimit;
    private final int maxAttempts;
    private final Duration lockWindow;
    private final Duration idleTimeout;

    public AdminSessionService(AdminRbacMapper rbacMapper,
                               AdminRbacService rbacService,
                               PasswordEncoder passwordEncoder,
                               StringRedisTemplate redis,
                               AuditService auditService,
                               com.newcoupon.infrastructure.audit.AuditActorResolver actorResolver,
                               SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry,
                               RedisIndexedSessionRepository sessionRepository,
                               @Value("${coupon.security.login.max-attempts:5}") int maxAttempts,
                               @Value("${coupon.security.login.ip-limit:10}") int loginIpLimit,
                               @Value("${coupon.security.login.lock-window:15m}") Duration lockWindow,
                               @Value("${coupon.security.session.idle-timeout:30m}") Duration idleTimeout) {
        this.rbacMapper = rbacMapper;
        this.rbacService = rbacService;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.auditService = auditService;
        this.actorResolver = actorResolver;
        this.sessionRegistry = sessionRegistry;
        this.sessionRepository = sessionRepository;
        this.maxAttempts = maxAttempts;
        this.loginIpLimit = loginIpLimit;
        this.lockWindow = lockWindow;
        this.idleTimeout = idleTimeout;
        this.dummyHash = passwordEncoder.encode("no-such-user-timing-equalizer");
    }

    /** 登录后返回给前端的身份与权限快照（/me 用同一形状）。 */
    public record AdminProfile(Long userId, String username, String displayName,
                               List<String> roles, List<String> permissions) {
    }

    /**
     * 登录：锁定检查 → 密码校验 → 建会话（轮换 id）→ 返回身份快照。
     *
     * @return 登录成功；失败时抛 {@link AdminLoginException}（401 + 7011，对外不分原因）
     */
    public AdminProfile login(String username, String rawPassword,
                              HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        // 用户名长度上限（审查修正：键内容不设限可被超长/随机用户名刷无界 Redis 键）
        if (username == null || username.isEmpty() || username.length() > 64) {
            throw new AdminLoginException();
        }
        if (overIpThrottle(request)) {
            audit(request, username, null, null, null, AUDIT_LOGIN_FAILED, Map.of("reason", "IP_THROTTLED"));
            throw new AdminLoginException();
        }
        AdminUserPO user = rbacService.findUser(username);
        // 锁定优先于密码比对：锁定期内哪怕密码正确也拒——否则爆破者可以边锁边试
        long failures = currentFailures(username, request);
        boolean locked = failures >= maxAttempts;

        String failReason = null;
        if (locked) {
            failReason = "LOCKED(failures=" + failures + ")";
        } else if (user == null) {
            passwordEncoder.matches(rawPassword, dummyHash);   // 纯耗时：与真实比对同量级
            registerFailure(username, request);
            failReason = "USER_NOT_FOUND";
        } else if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            long after = registerFailure(username, request);
            if (after >= maxAttempts) {
                // 恰好在本次跨过阈值：单独记一条锁定审计（运维要能回答"谁被锁了、何时能解"）
                audit(request, username, null, null, null, AUDIT_LOCKED,
                        Map.of("failures", after, "lockWindowMs", lockWindow.toMillis()));
            }
            failReason = "BAD_PASSWORD";
        } else if (user.getEnabled() == null || user.getEnabled() != 1) {
            // 停用判断放在密码比对**之后**（审查修正）：放在前面的话，停用账号
            // "不跑 BCrypt 也不计数"，与不存在用户名在耗时和锁定动力学上双差异——
            // 探测者可借此确认"该用户名存在且已停用"。现在：密码错=与不存在同路径；
            // 密码对才暴露停用（能给出正确密码的不是探测者）
            failReason = "USER_DISABLED";
        }

        if (failReason != null) {
            audit(request, username, null, null, null, AUDIT_LOGIN_FAILED, Map.of("reason", failReason));
            throw new AdminLoginException();
        }

        // ---- 通过：建会话 ----
        // 先轮换 session id（会话固定攻击防护：攻击者预种的 cookie 指向旧 id，登录后即作废），
        // 再写认证上下文。changeSessionId 后 Spring Session 会在响应提交时下发新 cookie。
        HttpSession session = request.getSession();
        request.changeSessionId();
        // 空闲超时（秒）：绝对超时在 Refresh 过滤器核对——Spring Session 只有空闲语义
        session.setMaxInactiveInterval((int) Math.max(1, idleTimeout.toSeconds()));
        session.setAttribute(AdminAuthorityRefreshFilter.SESSION_AUTH_VERSION, user.getAuthVersion());
        session.setAttribute(AdminAuthorityRefreshFilter.SESSION_LOGIN_AT, System.currentTimeMillis());

        Authentication authentication = buildAuthentication(user);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // 写进会话（SPRING_SECURITY_CONTEXT）：下一个请求由安全链自动装载；
        // 副作用（正是我们要的）：索引仓按 Authentication.getName()=登录名建立会话索引
        securityContextRepository.saveContext(context, request, response);

        // 成功即清失败计数：锁定只累计"连续"失败
        redis.delete(FAIL_KEY_PREFIX + username);

        audit(request, username, user.getId(), String.valueOf(user.getId()), session.getId(), AUDIT_LOGIN_SUCCESS, null);
        return profileOf(user);
    }

    /** 登出：删会话（幂等——没登录也 200）。 */
    public void logout(HttpServletRequest request) {
        Object principal = currentPrincipal();
        HttpSession session = request.getSession(false);
        // 会话 id 先取后失效：invalidate 之后再读 id 会抛 IllegalStateException，
        // 而"登出发生在哪个会话"正是这条审计要留的事实
        String sessionId = session == null ? null : session.getId();
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        if (principal instanceof AdminPrincipal admin) {
            audit(request, admin.getUsername(), admin.getUserId(), String.valueOf(admin.getUserId()), sessionId, AUDIT_LOGOUT, null);
        }
    }

    /**
     * 当前登录身份 + 最新权限（权限此时已被 AdminAuthorityRefreshFilter 按请求重载，
     * 这里只补齐 roles 展示与 displayName）。
     */
    public AdminProfile me() {
        Object principal = currentPrincipal();
        if (!(principal instanceof AdminPrincipal admin)) {
            throw new NotLoggedInException();
        }
        AdminUserPO user = rbacService.findUserById(admin.getUserId());
        return profileOf(user);
    }

    /**
     * 强制下线：删目标用户的**全部**会话 + auth_version+1（双保险：就算某个会话键漏删，
     * 版本也对不上了）。目标不存在时静默成功（幂等，不泄露用户名存在性）。
     */
    public int forceLogout(String targetUsername, String operatorNote) {
        AdminUserPO target = rbacService.findUser(targetUsername);
        if (target == null) {
            return 0;
        }
        int removed = 0;
        for (var info : sessionRegistry.getAllSessions(targetUsername, false)) {
            sessionRepository.deleteById(info.getSessionId());
            removed++;
        }
        rbacMapper.bumpAuthVersion(target.getId());
        log.warn("ADMIN FORCE LOGOUT: username={} 删除会话 {} 个（{}）",
                targetUsername, removed, operatorNote);
        // actor=执行操作的管理员（审查 m2：此前记成被下线者——"谁把谁踢下线"只答了一半）；
        // 被下线目标进 resourceId 与 user_id（操作对象维度），detail 带目标用户名便于 grep
        com.newcoupon.infrastructure.audit.AuditActorResolver.Actor operator = actorResolver.resolve();
        audit(null, targetUsername, target.getId(), operator.actorId(), operator.sessionFingerprint(),
                AUDIT_FORCE_LOGOUT,
                Map.of("removedSessions", removed,
                        "note", String.valueOf(operatorNote),
                        "target", targetUsername));
        return removed;
    }

    /**
     * 会话动作审计的统一出口（V6 2C 起带 actor 三列）。
     *
     * <p>actor 口径：ADMIN + **执行动作的管理员** userId（登录/登出=本人；强制下线=操作者，
     * 见 {@link #forceLogout}）；sessionId=会话**指纹**（原值是 SESSION Cookie 凭据本身，
     * 审计行可被低权限角色查到——原值入库=劫持提权链，审查 C2；指纹在本出口统一转换，
     * 调用方传原值也不会落库）。resourceId 放 username（人会读），actorId 放 userId
     * （程序 join admin_user 用），两个维度都要能查。
     */
    private void audit(HttpServletRequest request, String resourceId, Long userId,
                       String actorId, String sessionId, String action, Map<String, ?> detail) {
        auditService.record(AuditService.AuditContext.of(
                "SYSTEM", userId, MDC.get(TraceIdFilter.TRACE_ID),
                action, "ADMIN_AUTH", resourceId,
                null, detail == null ? null : AuditService.serialize(detail),
                request == null ? null : RiskControlFilter.clientIp(request), 0,
                "ADMIN", actorId, com.newcoupon.infrastructure.audit.AuditActorResolver.fingerprint(sessionId)));
    }

    /** 会话里重建 Authentication 用（登录时）；之后每请求由 Refresh 过滤器按 DB 重载。 */
    private Authentication buildAuthentication(AdminUserPO user) {
        List<SimpleGrantedAuthority> authorities = rbacService.permissionCodes(user.getId())
                .stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(user.getId(), user.getUsername()), null, authorities);
    }

    private AdminProfile profileOf(AdminUserPO user) {
        if (user == null) {   // 用户已被物理删除（会话还在的极短窗口）
            throw new NotLoggedInException();
        }
        return new AdminProfile(user.getId(), user.getUsername(), user.getDisplayName(),
                rbacService.roleCodes(user.getId()),
                rbacService.permissionCodes(user.getId()));
    }

    private Object currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getPrincipal();
    }

    private long currentFailures(String username) {
        String v = redis.opsForValue().get(FAIL_KEY_PREFIX + username);
        return v == null ? 0 : Long.parseLong(v);
    }

    /**
     * 记一次失败并返回窗口内累计数。未知用户名同样计数：不数的话，探测者可用
     * "失败后第 6 次还能不能继续错"反推用户名存在性；数了，两种情况行为一致。
     */
    /**
     * 第二管理员凭据核验（双人删除用）：与登录同一批 BCrypt 哈希、同一防枚举立场，
     * **并且接入同一锁定计数**——否则这里成为绕过登录锁定的密码爆破旁路
     * （审查发现：无计数无节流，且可在应用存在性校验之前反复试）。
     * 键名空间独立（approver:）与登录计数互不干扰，但阈值/窗口同配置。
     * @return true=凭据有效；false=无效或已锁定（不区分，防枚举）
     */
    public boolean verifyApproverCredential(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()
                || username.length() > 64) {
            return false;
        }
        String key = "c:admin:auth:approver:" + username;
        try {
            String existing = redis.opsForValue().get(key);
            if (existing != null && Long.parseLong(existing) >= maxAttempts) {
                return false;   // 已锁定：正确密码也拒（与登录同语义）
            }
            AdminUserPO approver = rbacMapper.findByUsername(username.trim());
            boolean ok = approver != null && approver.getEnabled() != null
                    && approver.getEnabled() == 1
                    && passwordEncoder.matches(password, approver.getPasswordHash());
            if (!ok) {
                Long n = redis.opsForValue().increment(key);
                if (n != null && n == 1) {
                    redis.expire(key, lockWindow);   // 固定窗口，与登录同款
                }
            } else {
                redis.delete(key);   // 核验通过清计数（复核成功不该累计）
            }
            return ok;
        } catch (RuntimeException e) {
            // Redis 故障 fail-closed：双人删除是不可逆操作，宁可拒绝不可绕过锁定
            log.warn("approver credential check degraded (fail-closed): user={}", username, e);
            return false;
        }
    }

    private long registerFailure(String username, HttpServletRequest request) {
        // 键=用户名+来源 IP（审查修正：只按用户名的话，未认证攻击者拿公开的种子
        // 用户名每 15 分钟补 1 次失败即可**永久锁死唯一管理员**；加 IP 后攻击者
        // 只能锁"自己 IP 视角"的管理员）。直连 remoteAddr——经反向代理部署时必须
        // 按实际拓扑改取受信的 X-Forwarded-For（runbook 有登记）
        String key = failKey(username, request);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            // 固定窗口：只在**首次**失败起 TTL——每次失败都续期等于滑动窗口，
            // 攻击者可以永不让你解锁（审查修正）
            redis.expire(key, lockWindow);
        }
        return count == null ? 0 : count;
    }

    private String failKey(String username, HttpServletRequest request) {
        return FAIL_KEY_PREFIX + username + ":" + request.getRemoteAddr();
    }

    private long currentFailures(String username, HttpServletRequest request) {
        String v = redis.opsForValue().get(failKey(username, request));
        return v == null ? 0 : Long.parseLong(v);
    }

    /**
     * 每来源 IP 登录节流（10 次/分钟）：锁定按用户名+IP 只护单个账号，
     * 这层护"一个 IP 对全体用户名的横向爆破"；顺带把超长/随机用户名洪水
     * （无界 Redis 键 + 每次一记 BCrypt 的 CPU DoS）压到固定速率内。
     * Redis 故障 fail-open（登录是可用性优先面，审计仍逐条留痕）。
     */
    private boolean overIpThrottle(HttpServletRequest request) {
        try {
            String key = "c:admin:auth:ip:" + request.getRemoteAddr();
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1) {
                redis.expire(key, Duration.ofMinutes(1));
            }
            return n != null && n > loginIpLimit;
        } catch (RuntimeException e) {
            log.warn("login ip throttle unavailable (fail-open)", e);
            return false;
        }
    }

    /** 登录失败异常：对外只有一种表现（401 + 7011），原因只在审计里。 */
    public static class AdminLoginException extends RuntimeException {
        public AdminLoginException() {
            super(ErrorCode.ADMIN_LOGIN_FAILED.getMessage());
        }
    }

    /** 未登录/会话失效：401 + 7010。 */
    public static class NotLoggedInException extends RuntimeException {
        public NotLoggedInException() {
            super(ErrorCode.ADMIN_SESSION_REQUIRED.getMessage());
        }
    }
}
