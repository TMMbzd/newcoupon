package com.newcoupon.infrastructure.security;

import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 旧管理令牌认证过滤器（V6 2A 第二刀：/api GET 全认证翻面，破坏性变更——
 * 公告见 docs/runbook/管理面认证翻面.md，回滚开关 coupon.security.api-read-auth-enabled）。
 *
 * <p><b>本刀改了什么（对外语义，2026-11-03 起）</b>：
 * <ul>
 *   <li><b>删掉了"GET 匿名放行"分支</b>——管理面（/api/**、/console**）的**所有方法**
 *       都要求"旧令牌或会话"认证。翻面前 /api 的 GET 是匿名可读的（只读面公开），
 *       翻面后匿名 GET 统一 401 + 7002。C 端业务写口（{@link #BUSINESS_WRITE_PATHS}）
 *       与身份自服务（/console/api/auth/**）豁免不变：前者有自己的防线（风控/幂等/状态机），
 *       后者是登录自举的前提。</li>
 *   <li><b>会话即过</b>——带有效控制台会话的请求在本过滤器之前就已持有 Authentication
 *       （HttpSessionSecurityContextRepository 从会话装载），本过滤器直接放行，
 *       会话的四道核对与权限重载由后置的 {@link AdminAuthorityRefreshFilter} 承接。
 *       即：认证判据 = "旧令牌 ∨ 会话"，两者取其一。</li>
 *   <li><b>旧令牌退役计时</b>——令牌命中时计数 {@code admin_legacy_token_usage_total}
 *       （Prometheus 渲染名；Micrometer 名 admin.legacy.token.usage）并首次 WARN 提醒迁移。
 *       一个发布周期后删除令牌路径时，用量为零是决策依据。</li>
 *   <li><b>回滚开关</b>——{@code coupon.security.api-read-auth-enabled=false} 恢复
 *       "只有写方法要令牌、GET 匿名放行"的旧语义（发布保险，一个发布周期后删除）。</li>
 * </ul>
 *
 * <p><b>翻面前不变的部分</b>：令牌未配置 fail-closed（对无会话的请求）；恒定时间比对；
 * 拒绝响应 401 + 7002 同一个 JSON 体；只认证不授权（@PreAuthorize 终审）；
 * 令牌持有者按"平台管理员全量权限"对待。
 *
 * <p>结构性守卫：AdminSurfaceProtectionTest 遍历全部 /api、/console 映射（含 GET）断言
 * "受保护 / 业务白名单 / 身份自服务"三态必居其一——以后任何人加出第四态测试直接红。
 */
public class LegacyAdminTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LegacyAdminTokenAuthenticationFilter.class);

    /**
     * 业务面写接口白名单（完整路径精确匹配）：C 端功能口，防线是风控闸/幂等/状态机而非管理令牌。
     * 注意：/open/v1/** 的同名接口走签名链，不经过本过滤器（前缀不含 /open）。
     * 2A-2 翻面不动这张表：翻的是"管理面 GET 匿名可读"，C 端业务口的匿名性是另一件事。
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

    /**
     * 旧令牌使用计数（Micrometer 名，Prometheus 渲染为 admin_legacy_token_usage_total）。
     * 退役决策依据：删除令牌路径前看这个值是否归零。
     */
    static final String LEGACY_TOKEN_USAGE_METRIC = "admin.legacy.token.usage";

    private final String adminToken;
    private final AdminRbacMapper rbacMapper;
    private final MeterRegistry meterRegistry;
    /** false = 回滚到 2A-1 语义（只有写方法要令牌，GET 匿名放行）。 */
    private final boolean apiReadAuthEnabled;
    /** 首次命中旧令牌时 WARN 一次的闸门：每请求都打会把日志刷成噪音。 */
    private final AtomicBoolean legacyMigrationWarned = new AtomicBoolean();

    public LegacyAdminTokenAuthenticationFilter(String adminToken, AdminRbacMapper rbacMapper,
                                                MeterRegistry meterRegistry, boolean apiReadAuthEnabled) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
        this.rbacMapper = rbacMapper;
        this.meterRegistry = meterRegistry;
        this.apiReadAuthEnabled = apiReadAuthEnabled;
        if (meterRegistry != null) {
            // 启动即注册（值 0）：lazy 注册会让"从未用过旧令牌"的服务在抓取文本里没有这行，
            // 仪表盘上"0 次使用"与"指标不存在"就分不开了——退役判断要的就是这个 0
            meterRegistry.counter(LEGACY_TOKEN_USAGE_METRIC);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (requiresAuth(request.getMethod(), request.getServletPath())) {
            // 会话先行：控制台登录过的请求此刻已带 Authentication（会话仓库在链前段装载），
            // 后置的 AdminAuthorityRefreshFilter 会做四道核对——这里只认"有非匿名身份"即放行
            if (hasSessionAuthentication()) {
                chain.doFilter(request, response);
                return;
            }
            String provided = request.getHeader(TOKEN_HEADER);
            if (!StringUtils.hasText(adminToken)) {
                log.error("ADMIN BLOCKED (fail-closed): 管理令牌未配置（coupon.admin.token 为空）"
                        + "——拒绝所有无会话的管理请求");
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
            countLegacyTokenUsage();
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
     * 有会话身份即过：SecurityContext 里已有"非匿名且已认证"的 Authentication
     * （本刀只有控制台会话这条来源——登录时由 HttpSessionSecurityContextRepository 写进会话）。
     * 匿名 token 也实现了 Authentication 且 isAuthenticated()=true，必须显式排除。
     */
    private boolean hasSessionAuthentication() {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        return existing != null && existing.isAuthenticated()
                && !(existing instanceof AnonymousAuthenticationToken);
    }

    /**
     * 旧令牌退役计时：计数 +1，并整进程只 WARN 一次迁移提醒。
     * meterRegistry 可能为 null（单元测试直接 new 过滤器时）——计数是观测，不是门禁，缺了不拦人。
     */
    private void countLegacyTokenUsage() {
        if (meterRegistry != null) {
            meterRegistry.counter(LEGACY_TOKEN_USAGE_METRIC).increment();
        }
        if (legacyMigrationWarned.compareAndSet(false, true)) {
            log.warn("ADMIN LEGACY TOKEN IN USE: 旧管理令牌仍在使用——将在一个发布周期后移除，"
                    + "迁移到 /console/api/auth 会话登录（公告：docs/runbook/管理面认证翻面.md）");
        }
    }

    /**
     * 判据本体（public 供结构性守卫测试直接调用）：管理面前缀 && 非业务白名单 && 非身份自服务。
     * 2A-2 翻面后方法不再参与判据（GET 与 POST 同样要认证）；参数保留是因为
     * 回滚开关在实例层 {@link #requiresAuth} 还要用它区分写方法。
     */
    public static boolean isProtected(String method, String servletPath) {
        return (servletPath.startsWith("/api/") || servletPath.startsWith("/console"))
                && !BUSINESS_WRITE_PATHS.contains(servletPath)
                && !servletPath.startsWith(AUTH_SERVICE_PREFIX);
    }

    /**
     * 本请求是否要过认证：翻面开 = {@link #isProtected} 原样（方法无关）；
     * 翻面关（回滚）= 再叠一层"写方法"判断，即 2A-1 的旧语义"GET 匿名放行"。
     */
    private boolean requiresAuth(String method, String servletPath) {
        if (apiReadAuthEnabled) {
            return isProtected(method, servletPath);
        }
        return isProtected(method, servletPath) && isWriteMethod(method);
    }

    /** 列举"什么是写方法"——比列举"要拦的方法"漏掉的可能性小（S2 教训）。仅回滚开关用到。 */
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
