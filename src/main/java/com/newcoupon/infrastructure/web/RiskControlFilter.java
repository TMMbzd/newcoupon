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
 * <p>IP 提取：**由容器解析**（Tomcat {@code RemoteIpValve}，见 {@link #clientIp} 的注释）——
 * 应用层读 {@code getRemoteAddr()}。生产形态是 Nginx TLS 终结+反代在前，
 * 真实 IP 只在代理头里；而"哪个头可信、取哪一跳"这件事由容器按**来源地址**判定，
 * 不由应用按头的内容判定（后者会被一个请求头绕过）。
 *
 * <p>拒绝形态：HTTP 429 + JSON 错误体（错误码 7001 风控域）——监控可单独统计
 * 风控命中率（M2 大盘的一个盘面）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // TraceIdFilter 之后：被拒的请求也有 traceId 可查
public class RiskControlFilter extends OncePerRequestFilter {

    /** 领券入口路径后缀（ReceiveController：@RequestMapping("/api/coupons") + @PostMapping("/receive")）。
     * L38 修复（评审 P1-11）：此前与 "/receive" 全等比较，而 getServletPath() 返回完整路径
     * "/api/coupons/receive"——判据永不匹配、IP 频控整体空转（过滤器单测直调故假绿）。 */
    private static final String RECEIVE_PATH_SUFFIX = "/receive";

    private final IpRateGate ipRateGate;
    /** 治理指标（S2 第 11 步） */
    private final com.newcoupon.infrastructure.metrics.GovernanceMetrics governanceMetrics;

    public RiskControlFilter(IpRateGate ipRateGate,
                             com.newcoupon.infrastructure.metrics.GovernanceMetrics governanceMetrics) {
        this.ipRateGate = ipRateGate;
        this.governanceMetrics = governanceMetrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod())
                && request.getServletPath().endsWith(RECEIVE_PATH_SUFFIX)) {
            String ip = clientIp(request);
            if (!ipRateGate.allow(ip)) {
                // 维度码用 risk_ip：与开放面配额过滤器里的 IP 维度（ip）区分开——
                // 两者是**不同的防线**（一个是风控、一个是配额），合并计数会看不清是谁在拦
                governanceMetrics.recordLimitRejected("risk_ip");
                response.setStatus(429);
                // 429 必须告诉对方多久后重试（第 6.5 步补：这条路的 429 一直缺这个头，
                // 而开放面配额那条早就有了——同一个契约，不该只在一半的出口上成立）。
                // 秒数由闸门自己算（配置只该有一个真相）。
                response.setHeader("Retry-After", String.valueOf(ipRateGate.retryAfterSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                // L43（2026-09-29 教学演示实测）：不声明字符集时 Servlet 默认 ISO-8859-1，
                // 手写的中文响应体变乱码（实测 {"code":7001,"message":"????????????"}）
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"code\":7001,\"message\":\"触发风控频控，请稍后再试\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 客户端 IP：**由容器解析，应用层不读任何代理头**（2026-10-08 第 6.5 步改）。
     *
     * <p><b>改之前错在哪</b>：这里原本自己解析 {@code X-Forwarded-For} 的**首跳**
     * （→ {@code X-Real-IP} → {@code remoteAddr}）。而 XFF 是**请求方可控的字符串**：
     * 攻击者每次都换一个假地址，就得到一个新的限流桶（**限流形同虚设**），
     * 审计落库的来源 IP 也变成他写的字符串（"日志投毒"，出了事追不到人）。
     * 这不是本项目特有的疏漏——Spring Cloud Gateway 的官方文档把"取 XFF 第一个值"
     * 直接点名成 *"This approach is vulnerable to spoofing"*；OWASP 也把
     * "信任客户端可控头"列为限流/访问控制绕过与日志投毒的成因。
     *
     * <p>而且"生产 Nginx 会覆盖代理头"这个前提**在仓库里无法验证**：若运维用的是
     * {@code $proxy_add_x_forwarded_for}（**追加**，这是更常见的写法），攻击者自带的头
     * 就会成为"首跳"。
     *
     * <p><b>现在怎么做</b>：交给容器（Tomcat 的 {@code RemoteIpValve}，
     * 由 {@code server.forward-headers-strategy=native} 启用）。它的规则才是对的：
     * <ol>
     *   <li>**只在"直连对端是可信代理"时才看这些头**——公网直连伪造头完全无效；</li>
     *   <li>**从右往左解析**，跳过内部/可信的跳，取**第一个不可信的**作为客户端地址
     *       （代理通常是往右边追加真实来源，所以最右边才是可信的）。</li>
     * </ol>
     * 于是"追加还是覆盖"对我们不再构成安全前提（两种写法都能拿到真实客户端），
     * 它降级成部署卫生（对 {@code X-Forwarded-Proto/Host/Port} 仍然必要）。
     *
     * <p><b>下游注意</b>：阀门会把消费掉的那部分 XFF 从请求里**删掉或改写**，
     * 所以**不要再读 {@code X-Forwarded-For}**——读到的要么是空、要么只剩攻击者那段。
     *
     * <p>被 {@code OpenApiAuthFilter}（审计记录来源 IP）与 {@code OpenAppController}（配置变更审计）
     * 共用，所以是公开的——**同一份提取规则只应有一处实现**，否则"审计记的 IP"和"风控拦的 IP"
     * 迟早会对不上。
     */
    public static String clientIp(HttpServletRequest request) {
        // 只有一行，但它承载的是"信任边界"：可信与否由容器按来源地址判定，不由我们按头判定。
        // **刻意不保留任何"头优先"的兜底分支**：留一个就留一个伪造入口，
        // 而且会让"到底以谁为准"变得说不清（排障时最容易在这里绕圈）。
        return request.getRemoteAddr();
    }
}
