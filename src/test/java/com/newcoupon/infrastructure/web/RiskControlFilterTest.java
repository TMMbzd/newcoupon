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
