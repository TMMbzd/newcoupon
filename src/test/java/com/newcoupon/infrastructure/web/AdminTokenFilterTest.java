package com.newcoupon.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * L38 回归（评审 P1-12）：管理面令牌 fail-closed——
 * 配置为空时必须拒绝所有管理写（默认值曾是 dev-admin-token，使该分支形同虚设；
 * 现默认空、dev 便利值挪 application-dev.yml）。
 */
class AdminTokenFilterTest {

    private MockHttpServletRequest post(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        if (token != null) {
            request.addHeader("X-Admin-Token", token);
        }
        return request;
    }

    @Test
    @DisplayName("令牌未配置（空）→ 401 + 7002 fail-closed（即使请求带了任何令牌）")
    void emptyToken_rejectsAll() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post("/api/templates", "dev-admin-token"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("7002");
        assertThat(chain.getRequest()).as("fail-closed：链不得继续").isNull();
    }

    @Test
    @DisplayName("错令牌 → 401；对令牌 → 放行")
    void wrongAndRightToken() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("s3cret");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        MockFilterChain chainDenied = new MockFilterChain();
        filter.doFilter(post("/api/templates", "wrong"), denied, chainDenied);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(chainDenied.getRequest()).isNull();

        MockHttpServletResponse allowed = new MockHttpServletResponse();
        MockFilterChain chainAllowed = new MockFilterChain();
        filter.doFilter(post("/api/templates", "s3cret"), allowed, chainAllowed);
        assertThat(chainAllowed.getRequest()).as("对令牌放行").isNotNull();
    }
}
