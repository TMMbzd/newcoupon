package com.newcoupon.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 管理面鉴权——<b>判据反转版</b>（2026-10-10 第 0 环裁定，治"漏列=裸奔"的结构性缺口）。
 *
 * <p><b>旧判据（白名单放行）的问题</b>：只保护显式列举的路径前缀，新增管理写接口
 * 忘了登记前缀 = 无鉴权写口——每一次加接口都在赌记性。
 *
 * <p><b>新判据（默认拒绝）</b>：管理面（/api/**、/console/**）的**非 GET** 请求默认必须带
 * X-Admin-Token；只有显式登记在 {@link #BUSINESS_WRITE_PATHS} 里的<b>业务面写接口</b>豁免
 * ——它们是 C 端功能口（领券/锁券/核销/释放），有各自的防线（风控闸、幂等、状态机），
 * 不该被管理令牌挡住。新增管理写接口<b>不需要</b>做任何登记即受保护；
 * 新增业务写接口才需要显式豁免（白名单是有意决策，看得见、审得出）。
 *
 * <p>结构性守卫：AdminSurfaceProtectionTest 遍历全部写映射断言"受保护或显式豁免"二态必居其一——
 * 以后任何人加了第三态（既不在防线也不在白名单的 /api 写口）测试直接红。
 *
 * <p>其余语义不变：GET 不设防（只读面）；令牌未配置 fail-closed（拒绝所有管理写）；
 * 恒定时间比对（防时序侧信道）；401 + 7002；放行/拒绝各一行日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)   // RiskControlFilter(+10) 之后
public class AdminTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    /**
     * 业务面写接口白名单（完整路径精确匹配）：C 端功能口，防线是风控闸/幂等/状态机而非管理令牌。
     * 注意：/open/v1/** 的同名接口走签名链，不经过本过滤器（前缀不含 /open）。
     */
    static final Set<String> BUSINESS_WRITE_PATHS = Set.of(
            "/api/coupons/receive",
            "/api/coupons/lock",
            "/api/coupons/confirm",
            "/api/coupons/release");

    private static final String TOKEN_HEADER = "X-Admin-Token";

    private final String adminToken;

    public AdminTokenFilter(@Value("${coupon.admin.token:}") String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
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
        }
        chain.doFilter(request, response);
    }

    /**
     * 判据本体（包内可见，供结构性测试直接调用）：写方法 && 管理面前缀 && 非业务白名单。
     */
    static boolean isProtected(String method, String servletPath) {
        return isWriteMethod(method)
                && (servletPath.startsWith("/api/") || servletPath.startsWith("/console"))
                && !BUSINESS_WRITE_PATHS.contains(servletPath);
    }

    /** 列举"什么是写方法"——比列举"要拦的方法"漏掉的可能性小（S2 教训）。 */
    private static boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":7002,\"message\":\"" + message + "\"}");
    }
}
