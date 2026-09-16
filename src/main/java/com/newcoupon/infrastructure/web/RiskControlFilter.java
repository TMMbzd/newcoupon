package com.newcoupon.infrastructure.web;

import com.newcoupon.infrastructure.risk.IpRateGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 风控前置过滤器（docs/11 §6）：只拦领券入口 {@code POST /receive}。
 *
 * <p>只配领券的裁决：频控有误伤成本，按攻击面配置——领券是唯一的"无成本获取资产"入口
 * （建券是管理面、查询无资产转移）。将来人机识别/名单（docs/11 §1）也挂这条拦截链。
 *
 * <p>IP 提取顺序：X-Forwarded-For 首跳 → X-Real-IP → remoteAddr——
 * 生产形态 Nginx TLS 终结+反代在前，真实 IP 只在代理头里（伪造防护由 Nginx 层
 * 覆盖代理头，应用信任第一跳）。
 *
 * <p>拒绝形态：HTTP 429 + JSON 错误体（错误码 7001 风控域）——监控可单独统计
 * 风控命中率（M2 大盘的一个盘面）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // TraceIdFilter 之后：被拒的请求也有 traceId 可查
public class RiskControlFilter extends OncePerRequestFilter {

    private static final String RECEIVE_PATH = "/receive";

    private final IpRateGate ipRateGate;

    public RiskControlFilter(IpRateGate ipRateGate) {
        this.ipRateGate = ipRateGate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && RECEIVE_PATH.equals(request.getServletPath())) {
            String ip = clientIp(request);
            if (!ipRateGate.allow(ip)) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"code\":7001,\"message\":\"触发风控频控，请稍后再试\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 代理头优先的客户端 IP 提取（X-Forwarded-For 首跳=最初的客户端） */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
