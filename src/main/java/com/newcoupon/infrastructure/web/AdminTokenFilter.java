package com.newcoupon.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * 管理面最小鉴权（L27 清偿，G 簇裁决提前实施——2026-09-21）。
 *
 * <p>为什么现在必须锁：P2b 后 publish 的效力从"重置 Redis 记账（对账可兜）"升格为
 * "铸造 DB allocated 配额 + 开桶"——无鉴权等于把"凭空造额度"的入口敞着。完整体系
 * （HMAC 签名/防重放/双人复核）仍按计划在 P7 管理面，这里先落最小版。
 *
 * <p>规则：POST /api/templates、/api/apps 开头的写操作必须带 X-Admin-Token 且与配置一致。
 * GET 查询不设防（只读面）。配置为空时 fail-closed（拒绝所有管理写——钱的方向宁严勿松）。
 *
 * <p>S1 扩展（2026-10-01）：接入方管理（/api/apps）纳入防线——注册应用会签发出可用于
 * 调用的密钥，与"铸造库存额度"同级，必须同样挡在令牌后面。
 * 比对用 {@link MessageDigest#isEqual}（恒定时间）——防时序侧信道逐字节猜令牌。
 *
 * <p>拒绝形态：401 + 错误码 7002（7xxx 安全域，与 IP 频控的 7001 同族）。
 * 审计：放行/拒绝各一行日志（完整审计表随 P7 变更治理）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)   // RiskControlFilter(+10) 之后
public class AdminTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    /** 受保护的管理面路径前缀：模板管理 + 接入方管理（S1） */
    private static final String[] ADMIN_PATH_PREFIXES = {"/api/templates", "/api/apps"};
    private static final String TOKEN_HEADER = "X-Admin-Token";

    private final String adminToken;

    public AdminTokenFilter(@Value("${coupon.admin.token:}") String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isWriteMethod(request.getMethod()) && isAdminPath(request.getServletPath())) {
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
     * 是否是需要保护的管理面写方法。
     *
     * <p><b>S2 修复（2026-10-04）</b>：原先只拦 POST。S2 要给租户配置加一个 PUT 接口——
     * 那个接口会绕过令牌检查。<b>"只拦 POST"是一个会随接口演进而失效的判据</b>：
     * 新增一个写方法就多一个缺口。改成列举"什么是写方法"，漏掉的可能就小得多。
     */
    private static boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method);
    }

    /** 是否属于受保护的管理面写路径（前缀匹配；注意用 getServletPath 而非 getRequestURI） */
    private static boolean isAdminPath(String servletPath) {
        for (String prefix : ADMIN_PATH_PREFIXES) {
            if (servletPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":7002,\"message\":\"" + message + "\"}");
    }
}
