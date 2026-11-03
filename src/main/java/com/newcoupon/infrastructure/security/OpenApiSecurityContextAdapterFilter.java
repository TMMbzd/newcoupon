package com.newcoupon.infrastructure.security;

import com.newcoupon.common.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 开放面认证适配器（V6 2A）：把 OpenApiAuthFilter 的 HMAC 认证结果"翻译"成 Spring Security 的
 * Authentication，让方法安全（@PreAuthorize）在共享业务方法上能看到开放面调用方的身份。
 *
 * <p><b>为什么需要它</b>：TemplateController 的建券/发布/库存调整同时映射在
 * {@code /api/templates*}（管理面）和 {@code /open/v1/templates*}（开放面）——同一段业务代码、
 * 两套身份体系（S1 D1）。2A 给这些方法加 {@code @PreAuthorize("hasAuthority('template:write')")}
 * 后，开放面的 HMAC 调用方如果不携带该权限，会被方法安全 403——这就改了既有对外语义。
 * 本过滤器把"能通过 HMAC 验签调开放面模板写接口"这个既有事实，原样表达成
 * Authentication 里的 {@code template:write} 权限。
 *
 * <p><b>两个安全边界</b>：
 * <ul>
 *   <li><b>只挂在 /open 链</b>（SecurityConfig 里只加进开放面链）：这份权限出了
 *       /open 前缀就不存在——HMAC 调用方拿它去调 {@code /api/templates} 时走的是
 *       anyRequest 链，那里没有本过滤器，也没有它的会话/令牌，先被旧令牌过滤器拦下。</li>
 *   <li><b>只信 HMAC 已验签的请求</b>：RequestContext.appId 由 OpenApiAuthFilter 在
 *       验签通过**之后**才写入（线程同一条，先于安全链执行）；没验签的请求 appId 为 null，
 *       不建立任何 Authentication。</li>
 * </ul>
 */
public class OpenApiSecurityContextAdapterFilter extends OncePerRequestFilter {

    /**
     * 开放面当前暴露的能力集（对应 /open/v1/templates* 写族：create/publish/stock-adjust/
     * freeze/unfreeze/offline/reonline——映射在同一个 Controller 上）。
     * 2A-2 给更多共享方法加注解时，这里同步补码；只要不加，开放面调不到的能力就不会被授予。
     */
    private static final Set<String> OPEN_SURFACE_AUTHORITIES = Set.of("template:write");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // appId 非空 = OpenApiAuthFilter 六步认证链已通过（同线程先执行）。
        // 这里不再做任何验证——它是"翻译"，不是第二道闸门。
        String appId = RequestContext.current().appId();
        if (appId != null) {
            List<SimpleGrantedAuthority> authorities = OPEN_SURFACE_AUTHORITIES.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "open:" + appId, null, authorities));
        }
        chain.doFilter(request, response);
    }
}
