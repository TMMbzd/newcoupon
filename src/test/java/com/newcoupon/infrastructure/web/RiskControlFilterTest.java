package com.newcoupon.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.infrastructure.risk.IpRateGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * L38 回归（评审 P1-11）：风控过滤器的路径判据必须匹配真实领券入口
 * （ReceiveController：@RequestMapping("/api/coupons") + @PostMapping("/receive")，
 * getServletPath() 返回完整路径 "/api/coupons/receive"）。
 *
 * <p>修复前：判据是与 "/receive" 全等比较——永不匹配、IP 频控整体空转；
 * 旧测试直调过滤器传 "/receive" 故假绿。本用例用**真实 servletPath** 钉住。
 */
class RiskControlFilterTest {

    private final IpRateGate gate = mock(IpRateGate.class);
    private final RiskControlFilter filter = new RiskControlFilter(gate,
            new com.newcoupon.infrastructure.metrics.GovernanceMetrics(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

    private MockHttpServletRequest post(String servletPath, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", servletPath);
        request.setServletPath(servletPath);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    /**
     * **客户端 IP 由容器解析，应用层不读任何代理头**（2026-10-08 第 6.5 步）。
     *
     * <p><b>红</b>：改前这里读的是 {@code X-Forwarded-For} 的**首跳**。那个值完全由请求方决定——
     * 攻击者每次换一个假地址，就得到一个干净的限流桶（限流形同虚设），审计里的来源 IP 也随之被污染。
     * Spring Cloud Gateway 的官方文档把这个反模式直接点名成
     * "This approach is vulnerable to spoofing"。
     *
     * <p><b>改法</b>：把"哪些头可信"交给容器（Tomcat 的 {@code RemoteIpValve}，
     * 由 {@code server.forward-headers-strategy=native} 启用）：它只在"直连对端是可信代理"时才看这些头，
     * 而且**从右往左跳过可信跳、取第一个不可信的**——那才是客户端地址。
     * 应用层只读 {@code getRemoteAddr()}，不给自己留任何"头优先"的兜底分支。
     */
    @Test
    @DisplayName("客户端 IP 只认容器解析的结果：带代理头也必须返回 remoteAddr")
    void clientIpDoesNotTrustHeaders() {
        MockHttpServletRequest request = post("/api/coupons/receive", "198.51.100.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Real-IP", "5.6.7.8");

        assertThat(RiskControlFilter.clientIp(request))
                .as("XFF / X-Real-IP 都是请求方可控的头。应用层直接采信 = 限流可被一个请求头绕过"
                        + "（换一个假地址就换一个干净的桶），审计里的来源 IP 也一起失真。"
                        + "容器已经替我们把这件事做对了：应用层只读 getRemoteAddr()")
                .isEqualTo("198.51.100.7");
    }

    /**
     * **429 的四要素对风控闸同样成立**（2026-10-08 第 6.5 步补）。
     *
     * <p><b>红</b>：改前这个 429 **没有 {@code Retry-After}**。接入方（或客户端重试中间件）
     * 拿不到"多久后可以再试"，只能瞎猜或立刻重试——后者会把限流变成自我加压。
     * 本项目在开放面配额那条路上早就补齐了这个头（S1 的 429 契约），风控这条漏了。
     */
    @Test
    @DisplayName("频控拒绝也要带 Retry-After（四要素对每条 429 都成立）")
    void deniedResponseCarriesRetryAfter() throws Exception {
        when(gate.allow("1.2.3.4")).thenReturn(false);
        when(gate.retryAfterSeconds()).thenReturn(2L);
        MockHttpServletRequest request = post("/api/coupons/receive", "1.2.3.4");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After"))
                .as("429 必须告诉对方多久后重试——否则客户端只能瞎猜，或立刻重试（把限流变成自杀）")
                .isEqualTo("2");
    }

    @Test
    @DisplayName("真实路径 /api/coupons/receive + 频控拒绝 → 429 + 7001，且不放行")
    void realPath_denied_returns429() throws Exception {
        when(gate.allow("1.2.3.4")).thenReturn(false);
        MockHttpServletRequest request = post("/api/coupons/receive", "1.2.3.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).as("真实领券路径必须被频控过滤器拦住").isEqualTo(429);
        assertThat(response.getContentAsString()).contains("7001");
        // L43（2026-09-29 教学演示实测发现）：手写响应体必须显式声明 UTF-8——
        // Servlet 默认 ISO-8859-1，不声明则中文提示在真实 HTTP 响应里变乱码
        // （实测响应头 charset=ISO-8859-1、体为 "????????????"）；MockHttpServletResponse 的
        // getContentAsString() 会自动按字符集解码，所以这里断言"解出来的中文可读"才钉得住。
        assertThat(response.getCharacterEncoding()).as("手写响应体必须声明 UTF-8").isEqualToIgnoringCase("UTF-8");
        assertThat(response.getContentAsString()).as("中文提示必须可读（不得乱码）")
                .contains("触发风控频控").contains("请稍后再试");
        assertThat(chain.getRequest()).as("拒绝后不得继续过滤链").isNull();
        verify(gate).allow("1.2.3.4");
    }

    @Test
    @DisplayName("真实路径 + 频控放行 → 链继续")
    void realPath_allowed_passesThrough() throws Exception {
        when(gate.allow("1.2.3.4")).thenReturn(true);
        MockHttpServletRequest request = post("/api/coupons/receive", "1.2.3.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("放行时过滤链继续").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("其他 POST 路径不受频控影响")
    void otherPath_notFiltered() throws Exception {
        MockHttpServletRequest request = post("/api/coupons/mycoupons", "1.2.3.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(gate, never()).allow(org.mockito.ArgumentMatchers.anyString());
    }
}
