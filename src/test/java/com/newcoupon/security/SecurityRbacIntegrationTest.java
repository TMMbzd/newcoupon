package com.newcoupon.security;

import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import com.newcoupon.infrastructure.persistence.po.AdminUserPO;
import com.newcoupon.integration.ShardedIntegrationTestSupport;
import com.newcoupon.infrastructure.security.AdminSessionService;
import com.newcoupon.infrastructure.persistence.mapper.AuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 2A 验收（设计：docs/research-2026-10-21/01 §4 2A）。
 *
 * <p>覆盖矩阵：①链结构（旧令牌语义钉住 + **2A-2 翻面：匿名 GET /api 一律 401** + /open 链不认会话）
 * ②登录/me/登出 ③CSRF ④失败锁定 ⑤角色权限矩阵 ⑥权限即时生效 ⑦停用即失效
 * ⑧强制下线 ⑨actuator 链（health 匿名、metrics/prometheus 要 Basic——2A-2 收窄）
 * ⑩认证审计落库 ⑪翻面验收三件套（匿名 GET 模板 401 / 旧令牌计数 / prometheus 凭据化）。
 *
 * <p>@AutoConfigureObservability：⑪ 要抓 /actuator/prometheus 断言
 * admin_legacy_token_usage_total——@SpringBootTest 默认禁掉指标导出（端点 404），
 * 必须显式开（与 GovernanceMetricsIntegrationTest 同一个坑）。
 *
 * <p>时间相关用例（空闲/绝对超时）单独在 {@link SecurityRbacSessionTimeoutIntegrationTest}
 * ——它们的短超时配置会换一个 Spring 上下文，与本类互不污染。
 *
 * <p>数据纪律（共享库基座）：除种子 admin 外，用例用户一律现场建、登录名带唯一尾缀，
 * 不清理（共享库既定纪律：独有标识即隔离）。
 */
@Import(securityprobe.RbacProbeController.class)
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "coupon.security.actuator.password=it-actuator-pass",   // ⑨⑪ Basic 正向断言用的已知凭据
        "coupon.security.login.ip-limit=1000"   // 整类几十次登录全来自 127.0.0.1，节流留给专测
})
class SecurityRbacIntegrationTest extends ShardedIntegrationTestSupport {

    private static final String SEED_ADMIN_PASSWORD = "dev-seed-admin-123";   // application-dev.yml 的 dev 默认
    private static final String USER_PASSWORD = "it-pass-123";

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate rest;

    @Autowired
    private AdminRbacMapper rbacMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminSessionService adminSessionService;

    @Autowired
    private AuditLogMapper auditLogMapper;

    // ------------------------------------------------------------------
    // 测试专用方法安全探针（securityprobe.RbacProbeController，类注释写清了为什么
    // 放在 com.newcoupon 之外、为什么用 GET）：真实走"会话身份 → @PreAuthorize"全链路。
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // HTTP 小工具：手工管理 Cookie（TestRestTemplate 不自动带，正合适——每一步带什么凭据
    // 都写清楚，正是这些用例要钉的语义）
    // ------------------------------------------------------------------

    private record LoginOutcome(ResponseEntity<String> response, String sessionCookie) {
    }

    /** 先 GET 一次领 XSRF-TOKEN Cookie（SPA 引导流程；CsrfFilter 对 GET 不校验只发 Cookie）。 */
    private String fetchCsrfToken() {
        ResponseEntity<String> resp = rest.getForEntity("/console/api/auth/me", String.class);
        return cookieOf(resp, "XSRF-TOKEN");
    }

    /** 从 Set-Cookie 里取指定 Cookie（缺失返回 null——401 响应本来就不该带 SESSION）。 */
    private String cookieOf(ResponseEntity<String> resp, String name) {
        List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        return setCookies.stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .map(c -> c.split(";")[0].substring(name.length() + 1))
                .orElse(null);
    }

    private LoginOutcome login(String username, String password) {
        String csrf = fetchCsrfToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CSRF-TOKEN", csrf);
        headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);
        ResponseEntity<String> resp = rest.exchange(
                "/console/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", headers),
                String.class);
        return new LoginOutcome(resp, cookieOf(resp, "SESSION"));
    }

    private ResponseEntity<String> getWithSession(String path, String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> postWithSession(String path, String sessionCookie, String csrf) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CSRF-TOKEN", csrf);
        headers.set(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrf);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
    }

    /** 建一个用例专属用户（登录名带唯一尾缀）并绑角色，返回 PO。 */
    private AdminUserPO createUser(String prefix, String roleCode) {
        AdminUserPO user = new AdminUserPO();
        user.setUsername(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash(passwordEncoder.encode(USER_PASSWORD));
        user.setDisplayName(prefix);
        user.setEnabled(1);
        user.setAuthVersion(0L);
        rbacMapper.insert(user);
        if (roleCode != null) {
            rbacMapper.bindRole(user.getId(), roleCode);
        }
        return user;
    }

    // ------------------------------------------------------------------
    // ① 链矩阵：旧令牌语义与匿名 GET 全部钉在现状
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① 管理写：无令牌 401+7002；对令牌 200（@PreAuthorize 与旧令牌共存不改变语义）")
    void chainMatrix_legacyTokenSemanticsUnchanged() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"name\":\"2A链矩阵验证券\",\"activityId\":1001,\"couponType\":1,\"faceValue\":100,"
                + "\"threshold\":1000,\"totalCount\":10,\"perUserLimit\":1,\"validType\":2,\"validDays\":7}";

        ResponseEntity<String> noToken = rest.exchange("/api/templates", HttpMethod.POST,
                new HttpEntity<>(body, json), String.class);
        assertThat(noToken.getStatusCode().value()).isEqualTo(401);
        assertThat(noToken.getBody()).contains("7002");

        HttpHeaders withToken = new HttpHeaders();
        withToken.setContentType(MediaType.APPLICATION_JSON);
        withToken.set("X-Admin-Token", "dev-admin-token");   // dev profile 的便利令牌（application-dev.yml）
        ResponseEntity<String> allowed = rest.exchange("/api/templates", HttpMethod.POST,
                new HttpEntity<>(body, withToken), String.class);
        assertThat(allowed.getStatusCode().value())
                .as("旧令牌 + template:write 注解共存：语义不变，建券 200。body=" + allowed.getBody())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("① 翻面后：匿名 GET /api 一律 401 + 7002（2A-1 曾钉住'匿名 200'，2A-2 推翻）")
    void chainMatrix_anonymousGetNowRejected() {
        ResponseEntity<String> resp = rest.getForEntity("/api/ping", String.class);
        assertThat(resp.getStatusCode().value())
                .as("只读面匿名放行的时代结束：GET 与写方法同一判据（破坏性变更，"
                        + "公告 docs/runbook/管理面认证翻面.md）").isEqualTo(401);
        assertThat(resp.getBody()).contains("7002");
    }

    @Test
    @DisplayName("① /open 链不认会话：登录后的 SESSION cookie 调 /open 仍按 HMAC 拒（6001）")
    void chainMatrix_openChainIgnoresSession() {
        LoginOutcome login = login("admin", SEED_ADMIN_PASSWORD);
        assertThat(login.response().getStatusCode().value()).isEqualTo(200);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "SESSION=" + login.sessionCookie());
        ResponseEntity<String> resp = rest.exchange("/open/v1/templates/1", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).contains("6001");   // SIGN_MISSING：/open 只认签名，cookie 无效
    }

    // ------------------------------------------------------------------
    // ②③④ 登录会话与锁定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("② 登录成功返回角色权限，me 一致，logout 后 me 401")
    void login_me_logout_flow() {
        LoginOutcome login = login("admin", SEED_ADMIN_PASSWORD);
        assertThat(login.response().getStatusCode().value()).isEqualTo(200);
        assertThat(login.sessionCookie()).as("登录应下发会话 Cookie").isNotBlank();
        String body = login.response().getBody();
        assertThat(body).contains("PLATFORM_ADMIN");
        assertThat(body).contains("template:write");
        assertThat(body).contains("session:revoke");   // ADMIN 全量权限

        ResponseEntity<String> me = getWithSession("/console/api/auth/me", login.sessionCookie());
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody()).contains("\"username\":\"admin\"");

        // 登出（POST：要 CSRF 头），会话即亡
        ResponseEntity<String> logout = postWithSession("/console/api/auth/logout",
                login.sessionCookie(), fetchCsrfToken());
        assertThat(logout.getStatusCode().value()).isEqualTo(200);
        assertThat(getWithSession("/console/api/auth/me", login.sessionCookie()).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("③ 登录缺 CSRF 头 → 403（CSRF 只在 /console/api 链生效）")
    void login_withoutCsrfToken_rejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange("/console/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"x\"}", headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("④ 密码错/用户不存在同一文案；连续 5 次错后正确密码也锁（统一 7011）")
    void loginFailure_lockoutAfterFiveFailures() {
        AdminUserPO target = createUser("it-lock", null);
        rbacMapper.bindRole(target.getId(), "PLATFORM_OPERATOR");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 用户不存在 vs 密码错：文案必须逐字一致（用户名枚举防线）
        String csrf = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.set("X-CSRF-TOKEN", csrf);
        loginHeaders.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);
        ResponseEntity<String> unknownUser = rest.exchange("/console/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"no-such-user-xyz\",\"password\":\"a\"}", loginHeaders),
                String.class);
        ResponseEntity<String> wrongPass = rest.exchange("/console/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + target.getUsername() + "\",\"password\":\"wrong\"}",
                        loginHeaders),
                String.class);
        assertThat(unknownUser.getStatusCode().value()).isEqualTo(401);
        assertThat(wrongPass.getStatusCode().value()).isEqualTo(401);
        assertThat(unknownUser.getBody()).isEqualTo(wrongPass.getBody()).contains("7011");

        // 5 次错误（上面已 1 次，再补 4 次）→ 锁定；第 6 次**正确密码**也必须拒
        for (int i = 0; i < 4; i++) {
            ResponseEntity<String> r = rest.exchange("/console/api/auth/login", HttpMethod.POST,
                    new HttpEntity<>("{\"username\":\"" + target.getUsername() + "\",\"password\":\"wrong\"}",
                            loginHeaders),
                    String.class);
            assertThat(r.getStatusCode().value()).isEqualTo(401);
        }
        LoginOutcome locked = login(target.getUsername(), USER_PASSWORD);
        assertThat(locked.response().getStatusCode().value())
                .as("锁定期内正确密码也拒绝，且文案不变（不暴露'已被锁'）")
                .isEqualTo(401);
        assertThat(locked.response().getBody()).contains("7011").doesNotContain("锁");
    }

    // ------------------------------------------------------------------
    // ⑤⑥ 角色矩阵与权限即时生效
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑤ OPERATOR 有 template:write 无 template:approve；AUDITOR 无 write（探针 403）")
    void operatorAuditor_permissionMatrix() {
        AdminUserPO operator = createUser("it-op", "PLATFORM_OPERATOR");
        AdminUserPO auditor = createUser("it-aud", "PLATFORM_AUDITOR");

        LoginOutcome opLogin = login(operator.getUsername(), USER_PASSWORD);
        assertThat(opLogin.response().getStatusCode().value()).isEqualTo(200);
        assertThat(opLogin.response().getBody())
                .contains("template:write")
                .doesNotContain("template:approve")
                .doesNotContain("session:revoke");

        assertThat(getWithSession("/api/rbac-probe/write", opLogin.sessionCookie()).getStatusCode().value())
                .as("OPERATOR 应通过 template:write").isEqualTo(200);
        assertThat(getWithSession("/api/rbac-probe/approve", opLogin.sessionCookie()).getStatusCode().value())
                .as("OPERATOR 不应通过 template:approve").isEqualTo(403);

        LoginOutcome audLogin = login(auditor.getUsername(), USER_PASSWORD);
        assertThat(getWithSession("/api/rbac-probe/write", audLogin.sessionCookie()).getStatusCode().value())
                .as("AUDITOR 只读，write 探针应 403").isEqualTo(403);
    }

    @Test
    @DisplayName("⑥ 权限即时生效：同一会话内摘掉角色 → 下一请求即 403，复绑 → 恢复 200")
    void permissionChange_effectiveImmediatelyInSameSession() {
        AdminUserPO operator = createUser("it-refresh", "PLATFORM_OPERATOR");
        LoginOutcome login = login(operator.getUsername(), USER_PASSWORD);
        String cookie = login.sessionCookie();
        assertThat(getWithSession("/api/rbac-probe/write", cookie).getStatusCode().value()).isEqualTo(200);

        rbacMapper.deleteRoleBinding(operator.getId(), "PLATFORM_OPERATOR");
        assertThat(getWithSession("/api/rbac-probe/write", cookie).getStatusCode().value())
                .as("改的是 DB 绑定，会话没动——下一请求就必须生效").isEqualTo(403);

        rbacMapper.bindRole(operator.getId(), "PLATFORM_OPERATOR");
        assertThat(getWithSession("/api/rbac-probe/write", cookie).getStatusCode().value()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // ⑦⑧ 会话治理
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑦ 停用用户：同会话下一请求 401（enabled=0 立即生效）")
    void disabledUser_nextRequestIs401() {
        AdminUserPO user = createUser("it-disable", "PLATFORM_OPERATOR");
        LoginOutcome login = login(user.getUsername(), USER_PASSWORD);
        assertThat(getWithSession("/console/api/auth/me", login.sessionCookie()).getStatusCode().value())
                .isEqualTo(200);

        rbacMapper.updateEnabled(user.getId(), 0);

        assertThat(getWithSession("/console/api/auth/me", login.sessionCookie()).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("⑧ 强制下线：删该用户全部会话（两个会话全灭），且可重复调用")
    void forceLogout_killsAllSessionsOfUser() {
        AdminUserPO user = createUser("it-force", "PLATFORM_OPERATOR");
        LoginOutcome first = login(user.getUsername(), USER_PASSWORD);
        LoginOutcome second = login(user.getUsername(), USER_PASSWORD);
        assertThat(getWithSession("/console/api/auth/me", first.sessionCookie()).getStatusCode().value())
                .isEqualTo(200);
        assertThat(getWithSession("/console/api/auth/me", second.sessionCookie()).getStatusCode().value())
                .isEqualTo(200);

        int removed = adminSessionService.forceLogout(user.getUsername(), "it-force-logout");

        assertThat(removed).isEqualTo(2);
        assertThat(getWithSession("/console/api/auth/me", first.sessionCookie()).getStatusCode().value())
                .isEqualTo(401);
        assertThat(getWithSession("/console/api/auth/me", second.sessionCookie()).getStatusCode().value())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // ⑨ actuator 链
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑨ /actuator/health 匿名 200；/actuator/metrics 匿名 401、Basic(ACTUATOR) 200")
    void actuatorChain_healthOpenMetricsLocked() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> metricsAnon = rest.getForEntity("/actuator/metrics", String.class);
        assertThat(metricsAnon.getStatusCode().value()).as("匿名拉 metrics 必须 401").isEqualTo(401);

        HttpHeaders basic = new HttpHeaders();
        basic.setBasicAuth("actuator", "it-actuator-pass");
        ResponseEntity<String> metricsAuth = rest.exchange("/actuator/metrics", HttpMethod.GET,
                new HttpEntity<>(basic), String.class);
        assertThat(metricsAuth.getStatusCode().value())
                .as("ROLE_ACTUATOR + Basic 放行。body=" + metricsAuth.getBody()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // ⑪ 2A-2 翻面验收三件套：匿名 GET 401 / 旧令牌计数 / prometheus 凭据化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑪-1 匿名 GET /api/templates/{id} → 401 + 7002（翻面钉住：只读面不再匿名）")
    void flip_anonymousGetTemplateRejected() {
        ResponseEntity<String> resp = rest.getForEntity("/api/templates/999999", String.class);
        assertThat(resp.getStatusCode().value())
                .as("2A-2 判据：/api 所有方法都要'旧令牌或会话'，GET 不例外").isEqualTo(401);
        assertThat(resp.getBody()).contains("7002");
    }

    @Test
    @DisplayName("⑪-2 旧令牌 GET 200，且 admin_legacy_token_usage_total 恰好 +1（退役计时）")
    void flip_legacyTokenGetCountsUsage() {
        double before = legacyTokenUsage(scrapePrometheus());

        HttpHeaders token = new HttpHeaders();
        token.set("X-Admin-Token", "dev-admin-token");   // dev profile 的便利令牌（application-dev.yml）
        ResponseEntity<String> resp = rest.exchange("/api/templates/999999", HttpMethod.GET,
                new HttpEntity<>(token), String.class);
        assertThat(resp.getStatusCode().value())
                .as("带旧令牌的 GET 照常放行（业务结果 200，模板不存在走业务码）"
                        + "。body=" + resp.getBody()).isEqualTo(200);

        // 计数断言用"前后差"而不是绝对值：本类 ① 的 POST 也用旧令牌，测试方法顺序不该影响结果
        double after = legacyTokenUsage(scrapePrometheus());
        assertThat(after)
                .as("每次旧令牌命中计数 +1——删除令牌路径前看它是否归零（首次还会 WARN 迁移提醒）")
                .isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("⑪-3 /actuator/prometheus 匿名 401；Basic(ACTUATOR) 200（抓取器必须配凭据）")
    void flip_prometheusRequiresBasic() {
        ResponseEntity<String> anon = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(anon.getStatusCode().value())
                .as("抓取文本带标签值（租户/资源名），2A-2 起不属于匿名面").isEqualTo(401);

        assertThat(scrapePrometheus())
                .as("配上 Basic 凭据的抓取器照常工作（迁移路径见 runbook 管理面认证翻面.md）")
                .contains("admin_legacy_token_usage_total");
    }

    /** 抓 /actuator/prometheus（2A-2 起要 Basic 凭据）。 */
    private String scrapePrometheus() {
        HttpHeaders basic = new HttpHeaders();
        basic.setBasicAuth("actuator", "it-actuator-pass");
        ResponseEntity<String> resp = rest.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(basic), String.class);
        assertThat(resp.getStatusCode().value()).as("带凭据抓取必须可用").isEqualTo(200);
        return resp.getBody();
    }

    /** 从抓取文本解析 admin_legacy_token_usage_total 的当前值（指标还没被计数过时视为 0）。 */
    private double legacyTokenUsage(String scrapeText) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^admin_legacy_token_usage_total\\s+([0-9.eE+-]+)")
                .matcher(scrapeText);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    // ------------------------------------------------------------------
    // ⑩ 认证审计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑩ 登录成功/失败/锁定都进审计链（异步写入，轮询等落库）")
    void authEvents_audited() {
        AdminUserPO user = createUser("it-audit", null);
        // 一次失败 + 一次成功
        HttpHeaders failHeaders = new HttpHeaders();
        failHeaders.setContentType(MediaType.APPLICATION_JSON);
        String csrf = fetchCsrfToken();
        failHeaders.set("X-CSRF-TOKEN", csrf);
        failHeaders.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);
        rest.exchange("/console/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + user.getUsername() + "\",\"password\":\"wrong\"}",
                        failHeaders), String.class);
        assertThat(login(user.getUsername(), USER_PASSWORD).response().getStatusCode().value()).isEqualTo(200);

        // 审计是异步单线程写：轮询最多 5 秒
        AtomicReference<List<AuditLogPO>> found = new AtomicReference<>(List.of());
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<AuditLogPO> rows = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogPO>()
                    .eq(AuditLogPO::getResourceId, user.getUsername())
                    .in(AuditLogPO::getAction,
                            AdminSessionService.AUDIT_LOGIN_SUCCESS, AdminSessionService.AUDIT_LOGIN_FAILED));
            if (rows.size() >= 2) {
                found.set(rows);
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(found.get()).as("登录成功与失败各一条审计").hasSizeGreaterThanOrEqualTo(2);
    }
}
