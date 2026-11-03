package com.newcoupon.infrastructure.security;

import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 旧管理令牌认证过滤器（V6 2A 由 {@code AdminTokenFilter} 改造，设计保留一个发布周期后删除）。
 *
 * <p><b>本次改造改了什么、没改什么</b>：
 * <ul>
 *   <li><b>没改对外语义</b>——判据仍是"判据反转版"：管理面（/api/**、/console**）的**非 GET**
 *       请求默认必须带 X-Admin-Token，白名单豁免的只有 C 端业务写口；令牌未配置 fail-closed；
 *       恒定时间比对；拒绝响应仍是 401 + 7002 同一个 JSON 体。本刀的全量回归绿就钉在这里。</li>
 *   <li><b>改了挂载方式</b>——不再做 {@code @Component} 全局 Servlet 过滤器（那会绕在安全链之外，
 *       且 Boot 双注册），改为由 {@link SecurityConfig} 手动 {@code new} 后
 *       {@code addFilterBefore} 进 Spring Security 链：认证发生在安全链内，
 *       认证结果（Authentication）才能被方法安全（@PreAuthorize）看见。</li>
 *   <li><b>改了成功后的动作</b>——旧版"校验通过就放行"（它自己就是终审）；现在多做一步
 *       <b>只认证、不授权</b>：通过后往 SecurityContext 放一个带全量权限码的 Authentication，
 *       授权交给 @PreAuthorize（2A-2 逐步接管）。令牌持有者按"平台管理员全量权限"对待——
 *       旧令牌从来就是管理员凭证，收窄它的权限等于这一刀就改对外语义。</li>
 *   <li><b>新增一处豁免</b>：{@code /console/api/auth/**}（登录/登出/me）。登录端点天然匿名可达，
 *       要求管理令牌等于没人能登录；它们的防线是 CSRF + 失败锁定 + 会话，不是管理令牌。</li>
 * </ul>
 *
 * <p>结构性守卫：AdminSurfaceProtectionTest 遍历全部写映射断言"受保护 / 业务白名单 / 身份自服务"
 * 三态必居其一——以后任何人加出第四态（都不沾的 /api、/console 写口）测试直接红。
 */
public class LegacyAdminTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LegacyAdminTokenAuthenticationFilter.class);

    /**
     * 业务面写接口白名单（完整路径精确匹配）：C 端功能口，防线是风控闸/幂等/状态机而非管理令牌。
     * 注意：/open/v1/** 的同名接口走签名链，不经过本过滤器（前缀不含 /open）。
     */
    public static final Set<String> BUSINESS_WRITE_PATHS = Set.of(
            "/api/coupons/receive",
            // 异步受理同样是无管理身份要求的 C 端业务口：走 Redis 准入、任务唯一键、
            // worker 硬日限/库存条件更新。漏登记会让 /api 异步入口被管理令牌挡住，
            // 而 /open 同名入口又能进——同一业务出现两套身份前提。
            "/api/coupons/receive-async",
            "/api/coupons/lock",
            "/api/coupons/confirm",
            "/api/coupons/release",
            "/api/coupons/refund");

    /**
     * 管理面身份自服务端点（登录/登出/me）：匿名可达是登录的前提。
     * 它们的防线是 CSRF（仅 /console/api/** 链）+ 失败锁定 + 会话过期，不是管理令牌。
     */
    public static final String AUTH_SERVICE_PREFIX = "/console/api/auth/";

    private static final String TOKEN_HEADER = "X-Admin-Token";

    private final String adminToken;
    private final AdminRbacMapper rbacMapper;

    public LegacyAdminTokenAuthenticationFilter(String adminToken, AdminRbacMapper rbacMapper) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
        this.rbacMapper = rbacMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isProtected(request.getMethod(), request.getServletPath())) {
            String provided = request.getHeader(TOKEN_HEADER);
            if (!StringUtils.hasText(adminToken)) {
                log.error("ADMIN BLOCKED (fail-closed): 管理令牌未配置（coupon.admin.token 为空）——拒绝所有管理写操作");
                reject(response, "管理通道未配置令牌，拒绝执行");
                return;
            }
            if (provided == null || !MessageDigest.isEqual(
                    adminToken.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
                log.warn("ADMIN REJECTED: path={} 提供令牌与配置不符", request.getServletPath());
                reject(response, "管理操作未授权");
                return;
            }
            log.info("ADMIN ALLOWED: path={}（管理令牌校验通过）", request.getServletPath());
            authenticateTokenHolder();
        }
        chain.doFilter(request, response);
    }

    /**
     * 令牌校验通过 → 建立认证。权限 = 权限码字典全量（查表而非硬编码：
     * 2A-2 继续加 @PreAuthorize 时旧令牌自动获得新权限码）。
     * 只认证不授权：要不要 template:write 由 @PreAuthorize 判断，本过滤器不再自裁。
     */
    private void authenticateTokenHolder() {
        List<String> codes = rbacMapper.findAllPermissionCodes();
        List<SimpleGrantedAuthority> authorities = codes.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "legacy-admin-token", null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * 判据本体（public 供结构性守卫测试直接调用）：写方法 && 管理面前缀 && 非业务白名单 && 非身份自服务。
     */
    public static boolean isProtected(String method, String servletPath) {
        return isWriteMethod(method)
                && (servletPath.startsWith("/api/") || servletPath.startsWith("/console"))
                && !BUSINESS_WRITE_PATHS.contains(servletPath)
                && !servletPath.startsWith(AUTH_SERVICE_PREFIX);
    }

    /** 列举"什么是写方法"——比列举"要拦的方法"漏掉的可能性小（S2 教训）。 */
    private static boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":7002,\"message\":\"" + message + "\",\"data\":null}");
    }
}
