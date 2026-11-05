package com.newcoupon.infrastructure.web;

import com.newcoupon.infrastructure.risk.RiskRequestAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 风控透传捕获过滤器（V6 4A）：把 {@code X-Device-Id} 头与客户端 IP 放进
 * {@link RiskRequestAttributes}（ThreadLocal），供应用服务层做风控决策——
 * **服务层不摸 HttpServletRequest**（领域层不该知道 HTTP 的存在，
 * 与 RequestContext 同一条设计边界）。
 *
 * <p><b>X-Device-Id 是透传维度，不是可信指纹</b>（S2 时代留的透传位设计）：
 * 头是请求方可控的，攻击者可以每次换一个——设备维度的价值在挡"不换头的脚本"
 * 与"多账号同设备聚集"的可观测性；商用设备指纹 SDK 只留端口（runbook §裁剪登记）。
 * 合同：≤128 字符（= risk_list_entry.value / risk_decision_log.device_id 列宽），
 * 超长或空白视为缺失（设备维度跳过标 degraded，不挡请求）。
 *
 * <p><b>IP 提取复用 {@link RiskControlFilter#clientIp}</b>——容器解析、应用层不读
 * 代理头。"同一份提取规则只应有一处实现"：审计记的 IP、频控拦的 IP、名单匹配的
 * IP 必须是同一个值，否则拉黑了 A 实际拦的是 B。
 *
 * <p><b>开销</b>：两个 ThreadLocal 操作/请求，不碰任何 IO——风控开关关闭时
 * 领券热路径的额外成本就是它（"关=零额外 IO"指 Redis/DB，见 RiskDecisionService）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)   // TraceIdFilter 之后、RiskControlFilter(+10) 之前：风控族过滤器里最早
public class RiskContextFilter extends OncePerRequestFilter {

    /** 与 risk_list_entry.value 列宽一致（也是给接入方的头合同） */
    static final int MAX_DEVICE_ID_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RiskRequestAttributes.set(new RiskRequestAttributes.Attributes(
                sanitizedDeviceId(request.getHeader("X-Device-Id")),
                RiskControlFilter.clientIp(request)));
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程池复用线程：漏清会把上一个请求的设备号/IP 带给下一个请求的风控决策
            RiskRequestAttributes.clear();
        }
    }

    /**
     * 头合同校验：去空白；超长（&gt;128）视为缺失而不是截断——截断会让两个不同的
     * 长设备号变成"同一设备"（错误聚合），缺失只是丢一个维度（fail-open 的既定语义）。
     */
    static String sanitizedDeviceId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DEVICE_ID_LENGTH) {
            return null;
        }
        return trimmed;
    }
}
