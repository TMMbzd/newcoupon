package com.newcoupon.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import com.newcoupon.infrastructure.security.LegacyAdminTokenAuthenticationFilter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * L38 回归（评审 P1-12）+ V6 2A 改造回归：管理面令牌 fail-closed——
 * 配置为空时必须拒绝所有管理写（默认值曾是 dev-admin-token，使该分支形同虚设；
 * 现默认空、dev 便利值挪 application-dev.yml）。
 *
 * <p>2A 改造后新增断言：令牌通过后要**建立认证**（Authentication 带全量权限码）——
 * 这是 @PreAuthorize 能看见旧令牌调用方的关键（只认证不授权，授权归方法安全）。
 */
class AdminTokenFilterTest {

    private final AdminRbacMapper rbacMapper = mock(AdminRbacMapper.class);

    private LegacyAdminTokenAuthenticationFilter filter(String token) {
        when(rbacMapper.findAllPermissionCodes()).thenReturn(List.of("template:write", "audit:read"));
        return new LegacyAdminTokenAuthenticationFilter(token, rbacMapper);
    }

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
        LegacyAdminTokenAuthenticationFilter filter = filter("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post("/api/templates", "dev-admin-token"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("7002");
        assertThat(chain.getRequest()).as("fail-closed：链不得继续").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("未认证：fail-closed 不建立任何身份").isNull();
    }

    @Test
    @DisplayName("错令牌 → 401；对令牌 → 放行且建立带全量权限码的认证")
    void wrongAndRightToken() throws Exception {
        LegacyAdminTokenAuthenticationFilter filter = filter("s3cret");

        MockHttpServletResponse denied = new MockHttpServletResponse();
        MockFilterChain chainDenied = new MockFilterChain();
        filter.doFilter(post("/api/templates", "wrong"), denied, chainDenied);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(chainDenied.getRequest()).isNull();

        MockHttpServletResponse allowed = new MockHttpServletResponse();
        MockFilterChain chainAllowed = new MockFilterChain();
        filter.doFilter(post("/api/templates", "s3cret"), allowed, chainAllowed);
        assertThat(chainAllowed.getRequest()).as("对令牌放行").isNotNull();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).as("对令牌 → 建立认证").isNotNull();
        assertThat(auth.getAuthorities().toString()).contains("template:write");
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("身份自服务端点（登录/登出）豁免旧令牌——登录天然匿名可达")
    void authEndpoints_bypassLegacyToken() throws Exception {
        LegacyAdminTokenAuthenticationFilter filter = filter("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post("/console/api/auth/login", null), response, chain);

        assertThat(chain.getRequest()).as("登录端点不得被管理令牌挡住").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
