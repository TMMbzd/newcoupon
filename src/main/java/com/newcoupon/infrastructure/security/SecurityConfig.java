package com.newcoupon.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.AuditService;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Security 6 三链装配（V6 2A 第一刀，设计：docs/research-2026-10-21/01 §4 2A）。
 *
 * <p><b>链结构</b>（FilterChainProxy 按序取第一个匹配的链）：
 * <pre>
 *   ① /open/**      permitAll + stateless + 无 CSRF——HMAC 由前置的 OpenApiAuthFilter（普通
 *                   Servlet 过滤器，@Order 更小，先于安全链执行）自己把关；本链只负责把已验签
 *                   的身份翻译成 Authentication（OpenApiSecurityContextAdapterFilter）。
 *   ② /actuator/**  health/info/prometheus 公开（沿用既有暴露面），其余 hasRole(ACTUATOR)
 *                   + httpBasic。**必须独立成链**：自定义 SecurityFilterChain 存在时 Boot 的
 *                   管理端安全自动配置退让，不单配就是整面裸奔（设计文档点名的坑）。
 *   ③ 其余(兜底)    /api/** 与 /console/api/**。本刀维持现状语义：web 层全放行，认证授权由
 *                   LegacyAdminTokenAuthenticationFilter（写路径要旧令牌，语义原样搬入）+
 *                   AdminAuthorityRefreshFilter（会话四道核对+权限重载）+ 方法安全承担。
 *                   CSRF 只对 /console/api/** 生效（Cookie + SPA 头）。
 * </pre>
 *
 * <p><b>本刀不做的翻面</b>（留给 2A-2）：/api GET 匿名可读、管理写路径的旧令牌要求——
 * 全部原样保留，全量回归绿是这刀的验收线。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity      // 打开 @PreAuthorize（第一刀落点：TemplateController 三个写方法）
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** 拒绝响应序列化器（静态——安全链出口不需要 Spring 定制的 mapper，与 AuditService 同款） */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CSRF 只保护的子面：管理台会话的写操作都在这个前缀下（登录/登出也是，SPA 先 GET 拿令牌）。 */
    static final String CONSOLE_API_PREFIX = "/console/api/";

    /** CSRF 安全方法集：GET/HEAD/OPTIONS/TRACE 不校验令牌（与 Spring Security 默认一致）。 */
    private static final Set<String> CSRF_SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    // ---------------------------------------------------------------------
    // 链 ①：开放面（/open/**）
    // ---------------------------------------------------------------------
    @Bean
    @Order(1)
    SecurityFilterChain openApiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/open/**")
                // HMAC 链对 Spring Security 全放行：认证结论由前置 OpenApiAuthFilter 给出
                //（拒绝时它自己写 401/6xxx 响应，根本进不到安全链）
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // 无状态：开放面凭签名不凭会话，带了 SESSION cookie 也当没有
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                // 已验签身份 → Authentication（见该过滤器类注释：为什么必须挂在链内）
                .addFilterAfter(new OpenApiSecurityContextAdapterFilter(), SecurityContextHolderFilter.class)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.SIGN_MISSING))
                        .accessDeniedHandler((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_FORBIDDEN, ErrorCode.RESOURCE_FORBIDDEN)));
        return http.build();
    }

    // ---------------------------------------------------------------------
    // 链 ②：actuator（独立链，防自动配置退让后裸奔）
    // ---------------------------------------------------------------------
    @Bean
    @Order(2)
    SecurityFilterChain actuatorChain(HttpSecurity http,
                                      UserDetailsService actuatorUserDetailsService) throws Exception {
        http.securityMatcher("/actuator/**")
                .authorizeHttpRequests(auth -> auth
                        // health/info/prometheus 沿用既有"匿名可读"语义（K8s 探针/抓取器无凭据）；
                        // 锁这三个端点的翻面要先给抓取器发凭据，归 2A-2
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        // 其余（metrics/env/...）一律要 ROLE_ACTUATOR + Basic 认证
                        .anyRequest().hasRole("ACTUATOR"))
                .userDetailsService(actuatorUserDetailsService)   // 只给本链：控制台用户不走 Basic
                .httpBasic(basic -> basic.realmName("newcoupon-actuator"))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    // ---------------------------------------------------------------------
    // 链 ③：兜底链（/api/**、/console/api/** 与其余一切）
    // ---------------------------------------------------------------------
    @Bean
    @Order(3)
    SecurityFilterChain defaultChain(HttpSecurity http,
                                     AdminRbacMapper rbacMapper,
                                     AuditService auditService,
                                     ObjectMapper objectMapper,
                                     @Value("${coupon.security.session.absolute-timeout:8h}")
                                     java.time.Duration absoluteTimeout,
                                     @Value("${coupon.admin.token:}") String adminToken) throws Exception {
        http
                // 本刀维持现状：web 层全放行。真正的门是——写路径的旧令牌过滤器（语义未动）、
                // /console/api/** 的 CSRF、方法安全 @PreAuthorize。2A-2 才把 /api GET 翻成需认证。
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // 会话按需创建：只有控制台登录（显式 getSession）会建立会话；
                // /api 业务调用全程不碰 session，不会凭空多出 Redis 会话与 Set-Cookie
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // CSRF：Cookie 仓（SPA 可读）+ 自定义头回传；**只保护 /console/api/** 的不安全方法**。
                // /api 机器面不设 CSRF——那里的写操作凭 X-Admin-Token（非 ambient 凭据，无 CSRF 面）。
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfCookieRepository())
                        .csrfTokenRequestHandler(spaCsrfRequestHandler())
                        .requireCsrfProtectionMatcher(consoleCsrfMatcher()))
                // 匿名调了只有会话/令牌才能过的方法安全 → 401(7010)；有权限不够 → 403(7012)，
                // 响应体与全站 Result 形状一致（默认 HTML 错误页对程序调用方没有意义）
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.ADMIN_SESSION_REQUIRED))
                        .accessDeniedHandler((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_FORBIDDEN, ErrorCode.ADMIN_FORBIDDEN)))
                // 两个管理面过滤器都手动 new + addFilterBefore：
                // 不加 @Component 的原因——Boot 会把 Filter 类型的 @Bean 再注册一遍全局 Servlet
                // 过滤器，等于同一段逻辑在安全链外又跑一次（设计文档明令避免的双注册）
                .addFilterBefore(
                        new LegacyAdminTokenAuthenticationFilter(adminToken, rbacMapper),
                        AuthorizationFilter.class)
                .addFilterBefore(
                        new AdminAuthorityRefreshFilter(rbacMapper, objectMapper, auditService, absoluteTimeout),
                        AuthorizationFilter.class);
        return http.build();
    }

    // ---------------------------------------------------------------------
    // 公共件
    // ---------------------------------------------------------------------

    /**
     * CSRF Cookie 仓：httpOnly=false（SPA 的 JS 要能读），头名用 X-CSRF-TOKEN。
     * SPA 无模板引擎渲染隐藏域，令牌只能走可读 Cookie + 请求头这对组合。
     */
    private static CookieCsrfTokenRepository csrfCookieRepository() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setHeaderName("X-CSRF-TOKEN");
        return repo;
    }

    /**
     * SPA 形态的 CSRF 请求处理器：{@code setCsrfRequestAttributeName(null)} 关掉"延迟解析"——
     * 令牌在每个请求都被实际加载，首次访问（哪怕 GET）就会种下 XSRF-TOKEN Cookie，
     * SPA 拿到 Cookie 才能发第一个写请求。这正是 Spring Security 官方 SPA 配方。
     */
    private static CsrfTokenRequestAttributeHandler spaCsrfRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /** CSRF 保护面 = /console/api/** 的不安全方法（POST/PUT/DELETE/PATCH）。 */
    private static RequestMatcher consoleCsrfMatcher() {
        return (HttpServletRequest request) -> !CSRF_SAFE_METHODS.contains(request.getMethod())
                && request.getServletPath().startsWith(CONSOLE_API_PREFIX);
    }

    /** 统一 JSON 拒绝响应（401/403 的安全链出口，形状与业务 Result 一致）。 */
    private static void writeJson(HttpServletResponse response, int status, ErrorCode code)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(Result.fail(code)));
    }

    /**
     * 密码编码器：DelegatingPasswordEncoder（哈希自带 {bcrypt} 前缀，将来换算法不动存量数据），
     * 缺省算法 BCrypt(12)——比默认 10 轮慢约 4 倍，针对的是低频登录口被离线爆破的场景。
     * 无前缀的旧哈希（未来迁移场景）按 BCrypt(12) 试配。
     */
    @Bean
    public static PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcrypt12 = new BCryptPasswordEncoder(12);
        DelegatingPasswordEncoder encoder =
                new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", bcrypt12));
        encoder.setDefaultPasswordEncoderForMatches(bcrypt12);
        return encoder;
    }

    /**
     * actuator 链专属用户：Basic 认证 + ROLE_ACTUATOR。独立于 admin_user 表——
     * 探测/抓取是**机器**凭据，与"人"的账号体系（会话/RBAC/审计）分开管理，
     * 轮换一个不影响另一个。
     */
    @Bean
    public UserDetailsService actuatorUserDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${coupon.security.actuator.username:actuator}") String username,
            @Value("${coupon.security.actuator.password:}") String password) {
        if (password == null || password.isBlank()) {
            // 未配置 = 本环境不打算开 Basic（探针只打 /health，不需要凭据）——
            // 用户名随机化，凭据不可猜测，等价于"配了也进不来"
            log.warn("SECURITY: coupon.security.actuator.password 未配置——actuator Basic 凭据随机生成，"
                    + "需要抓取 metrics 的环境请配置 ACTUATOR_AUTH_PASSWORD");
            return new InMemoryUserDetailsManager(
                    User.withUsername("actuator-" + java.util.UUID.randomUUID())
                            .password(passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                            .roles("ACTUATOR")
                            .build());
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username)
                        .password(passwordEncoder.encode(password))
                        .roles("ACTUATOR")
                        .build());
    }
}
