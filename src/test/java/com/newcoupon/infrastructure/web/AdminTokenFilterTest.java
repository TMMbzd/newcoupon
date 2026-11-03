package com.newcoupon.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import com.newcoupon.infrastructure.security.LegacyAdminTokenAuthenticationFilter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * L38 回归（评审 P1-12）+ V6 2A 改造回归：管理面令牌 fail-closed——
 * 配置为空时必须拒绝所有管理请求（默认值曾是 dev-admin-token，使该分支形同虚设；
 * 现默认空、dev 便利值挪 application-dev.yml）。
 *
 * <p>2A 改造后新增断言：令牌通过后要**建立认证**（Authentication 带全量权限码）——
 * 这是 @PreAuthorize 能看见旧令牌调用方的关键（只认证不授权，授权归方法安全）。
 *
 * <p>2A-2 翻面后再钉三件事：GET 与 POST 同样要认证（匿名 GET 分支已删）；
 * 已有会话身份的请求不再要求令牌（"旧令牌 ∨ 会话"取其一）；回滚开关关掉后
 * GET 恢复匿名放行（发布保险，与翻面前语义一致）。
 */
class AdminTokenFilterTest {

    private final AdminRbacMapper rbacMapper = mock(AdminRbacMapper.class);

    /** SecurityContextHolder 是线程级全局：上一个用例建立的认证不清掉会串进下一个用例。 */
    @BeforeEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
    }

    /** 翻面开（默认）的过滤器：meterRegistry 传 null——计数是观测不是门禁，单测不设桩。 */
    private LegacyAdminTokenAuthenticationFilter filter(String token) {
        return filter(token, true);
    }

    /** $2 = 回滚开关：false 走 2A-1 旧语义（只有写方法要令牌）。 */
    private LegacyAdminTokenAuthenticationFilter filter(String token, boolean apiReadAuthEnabled) {
        when(rbacMapper.findAllPermissionCodes()).thenReturn(List.of("template:write", "audit:read"));
        return new LegacyAdminTokenAuthenticationFilter(token, rbacMapper, null, apiReadAuthEnabled);
    }

    private MockHttpServletRequest post(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        if (token != null) {
            request.addHeader("X-Admin-Token", token);
        }
        return request;
    }

    /** GET 请求构造（2A-2 起管理面 GET 不再是"天然放行"的方法）。 */
    private MockHttpServletRequest get(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
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

    @Test
    @DisplayName("2A-2 翻面：匿名 GET /api → 401 + 7002（只读面不再匿名放行）")
    void flipOn_getWithoutTokenRejected() throws Exception {
        LegacyAdminTokenAuthenticationFilter filter = filter("s3cret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get("/api/templates", null), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("7002");
        assertThat(chain.getRequest()).as("匿名 GET 不得继续往下走").isNull();
    }

    @Test
    @DisplayName("2A-2 翻面：带令牌 GET → 放行（GET 与写方法同一判据）")
    void flipOn_getWithTokenPasses() throws Exception {
        LegacyAdminTokenAuthenticationFilter filter = filter("s3cret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get("/api/templates", "s3cret"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("对令牌的 GET 放行").isNotNull();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("会话身份先行：Context 里已有认证 → 不要求令牌（会话核对归 AdminAuthorityRefreshFilter）")
    void sessionAuthentication_passesWithoutToken() throws Exception {
        LegacyAdminTokenAuthenticationFilter filter = filter("");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-user", null, List.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // 令牌未配置也放行：会话本身就是有效凭证，fail-closed 针对的是"令牌+会话都没有"的请求
        filter.doFilter(get("/api/templates", null), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("有会话身份即过").isNotNull();
    }

    @Test
    @DisplayName("回滚开关（api-read-auth-enabled=false）：GET 恢复匿名放行，写仍要令牌")
    void rollbackSwitch_restoresOldGetSemantics() throws Exception {
        LegacyAdminTokenAuthenticationFilter filter = filter("s3cret", false);

        MockHttpServletResponse getResp = new MockHttpServletResponse();
        MockFilterChain getChain = new MockFilterChain();
        filter.doFilter(get("/api/templates", null), getResp, getChain);
        assertThat(getResp.getStatus()).as("回滚=2A-1 语义：GET 匿名放行").isEqualTo(200);
        assertThat(getChain.getRequest()).isNotNull();

        MockHttpServletResponse postResp = new MockHttpServletResponse();
        MockFilterChain postChain = new MockFilterChain();
        filter.doFilter(post("/api/templates", null), postResp, postChain);
        assertThat(postResp.getStatus()).as("回滚不豁免写方法：匿名 POST 仍 401").isEqualTo(401);
    }
}
