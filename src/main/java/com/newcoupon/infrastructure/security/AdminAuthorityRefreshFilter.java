package com.newcoupon.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.AuditService;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import com.newcoupon.infrastructure.persistence.po.AdminUserPO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 会话权限即时生效过滤器（V6 2A）：每个**已登录**的管理面请求，从 DB 重载权限并核对会话有效性。
 *
 * <p><b>四道核对</b>（任一不过 → 删会话 + 401 + 审计，响应体统一 7010）：
 * <ol>
 *   <li>用户行还在（被删了）；</li>
 *   <li>{@code enabled=1}（停用 → 该用户全部会话立刻全灭）；</li>
 *   <li>会话里的登录时 {@code auth_version} 与 DB 一致（改密/强制重登 bump 过 → 会话失效）；</li>
 *   <li>距登录未超过绝对时长上限（空闲超时由 Spring Session 的 maxInactiveInterval 管，
 *       绝对上限在这里管——防"一直活跃就永不过期"的会话长生）。</li>
 * </ol>
 *
 * <p><b>为什么每请求查一次库而不缓存</b>（设计裁定）：管理面是人肉低频面，一次两跳 join
 * 的成本可以忽略；换来的是"改角色绑定/停用/改密对存量会话**下一个请求**就生效"——
 * 缓存方案（TTL 或失效广播）都存在"改了但没生效"的窗口，那是安全变更最坏的窗口。
 *
 * <p>本过滤器不做授权判断：重载出的权限码交给 @PreAuthorize 用。
 */
public class AdminAuthorityRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthorityRefreshFilter.class);

    /** 会话属性名：登录时的 auth_version 快照（与 DB 比对，见第 3 道核对）。 */
    public static final String SESSION_AUTH_VERSION = "ADMIN_AUTH_VERSION";
    /** 会话属性名：登录时刻 epoch millis（绝对时长的起点）。 */
    public static final String SESSION_LOGIN_AT = "ADMIN_LOGIN_AT";

    private final AdminRbacMapper rbacMapper;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final Duration absoluteTimeout;

    public AdminAuthorityRefreshFilter(AdminRbacMapper rbacMapper,
                                       ObjectMapper objectMapper,
                                       AuditService auditService,
                                       @Value("${coupon.security.session.absolute-timeout:8h}")
                                       Duration absoluteTimeout) {
        this.rbacMapper = rbacMapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.absoluteTimeout = absoluteTimeout;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Object principal = currentPrincipal();
        if (principal instanceof AdminPrincipal admin) {
            var session = request.getSession(false);
            if (session == null) {
                // 身份来自会话而会话已不在（过期瞬间的极窄窗口）：按未登录处理，
                // 不能静默 200 空响应——那会让前端以为"成功但没数据"
                SecurityContextHolder.clearContext();
                reject(response);
                return;
            }
            if (!validateAndRefresh(request, response, admin, session)) {
                return;   // 响应已写好（401 + 7010），不再继续链
            }
        }
        chain.doFilter(request, response);
    }

    /** 只处理会话用户：匿名/业务调用/旧令牌持有者不经过本过滤器的任何核对。 */
    private Object currentPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getPrincipal();
    }

    /**
     * 四道核对 + 权限重载。
     *
     * @return false = 会话已失效（响应已写，调用方不得继续链）；true = 继续
     */
    private boolean validateAndRefresh(HttpServletRequest request, HttpServletResponse response,
                                       AdminPrincipal admin, jakarta.servlet.http.HttpSession session)
            throws IOException {
        AdminUserPO user = rbacMapper.findById(admin.getUserId());
        Long loginVersion = (Long) session.getAttribute(SESSION_AUTH_VERSION);
        Long loginAt = (Long) session.getAttribute(SESSION_LOGIN_AT);

        String reason = terminateReason(user, loginVersion, loginAt);
        if (reason != null) {
            // 会话 id 先取后失效：invalidate 后再读会抛 IllegalStateException
            String terminatedSessionId = session.getId();
            // 删会话：既有 Redis 会话行，也清本请求的 SecurityContext——后续过滤器看到的是匿名
            session.invalidate();
            SecurityContextHolder.clearContext();
            log.warn("ADMIN SESSION TERMINATED: user={} username={} reason={}",
                    admin.getUserId(), admin.getUsername(), reason);
            auditService.record(AuditService.AuditContext.of(
                    "SYSTEM", admin.getUserId(),
                    org.slf4j.MDC.get(com.newcoupon.infrastructure.web.TraceIdFilter.TRACE_ID),
                    "ADMIN_AUTH_SESSION_TERMINATED", "ADMIN_SESSION", admin.getUsername(),
                    null, AuditService.serialize(java.util.Map.of("reason", reason)),
                    com.newcoupon.infrastructure.web.RiskControlFilter.clientIp(request),
                    ErrorCode.ADMIN_SESSION_REQUIRED.getCode(),
                    "ADMIN", String.valueOf(admin.getUserId()), terminatedSessionId));
            reject(response);
            return false;
        }

        // 核对全过 → 用**当前时刻**的权限码重建 Authentication（只放本请求的 Context，
        // 不回写会话：会话里永远只有身份，权限的真相只活在 DB 里）
        List<SimpleGrantedAuthority> authorities = rbacMapper.findPermissionCodes(admin.getUserId())
                .stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, authorities));
        return true;
    }

    /** 四道核对的归一出口：返回 null=全过；否则返回人话原因（日志用，不进响应）。 */
    private String terminateReason(AdminUserPO user, Long loginVersion, Long loginAt) {
        if (user == null) {
            return "USER_DELETED";
        }
        if (user.getEnabled() == null || user.getEnabled() != 1) {
            return "USER_DISABLED";
        }
        if (loginVersion == null || user.getAuthVersion() == null
                // Long 是对象：必须 equals（!= 比较引用，128 以上装箱必踩）
                || !loginVersion.equals(user.getAuthVersion())) {
            return "AUTH_VERSION_CHANGED";
        }
        if (loginAt == null
                || System.currentTimeMillis() - loginAt > absoluteTimeout.toMillis()) {
            return "ABSOLUTE_TIMEOUT";
        }
        return null;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ErrorCode.ADMIN_SESSION_REQUIRED)));
    }
}
