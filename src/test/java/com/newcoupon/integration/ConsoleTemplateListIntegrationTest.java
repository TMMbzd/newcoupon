package com.newcoupon.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 2D 裁剪版验收（设计：docs/research-2026-10-21/01 §4 2D 的裁剪刀，runbook：
 * docs/runbook/控制台最小页.md）。控制台最小页 = 登录 + 模板列表，四个钉子：
 *
 * <ol>
 *   <li><b>未认证 401</b>：匿名 GET /api/templates → 401 + 7002（2A-2 翻面语义的延续，
 *       列表是管理面读接口，不因"控制台要用"而开口子）；</li>
 *   <li><b>会话登录后 200 且分页形状对</b>：控制台登录（CSRF 流）→ GET 列表 →
 *       total/page/size/rows 齐全，rows 含造出的两个模板；顺带钉 template:read
 *       的 @PreAuthorize：无角色用户 403；</li>
 *   <li><b>分页边界不炸</b>：page 超界返回空 rows 而非报错；page&lt;1 / size&gt;100 拒绝；</li>
 *   <li><b>静态资源可服务</b>：GET /console/ 返回 HTML 且其引用的 js 真实存在——
 *       防"index.html 在、构建产物丢"的假绿（Vite 与降级静态版同判据）。</li>
 * </ol>
 *
 * <p>数据纪律（共享库基座）：模板走真实 API 建（旧令牌 + template:write），
 * 名称带唯一尾缀；无角色用户现场建，不清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 整类多次登录全来自 127.0.0.1，节流留给专测（与 SecurityRbacIntegrationTest 同款）
        "coupon.security.login.ip-limit=1000"
})
class ConsoleTemplateListIntegrationTest extends ShardedIntegrationTestSupport {

    private static final String SEED_ADMIN_PASSWORD = "dev-seed-admin-123";   // application-dev.yml 的 dev 默认
    private static final String USER_PASSWORD = "it-pass-123";
    private static final ObjectMapper JSON = new ObjectMapper();

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
    private com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper rbacMapper;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------
    // HTTP 小工具：与 SecurityRbacIntegrationTest 同款（手工管 Cookie，
    // 每一步带什么凭据写清楚——这正是本类要钉的语义）
    // ------------------------------------------------------------------

    /** 先 GET 一次领 XSRF-TOKEN Cookie（SPA 引导流程；CsrfFilter 对 GET 不校验只发 Cookie）。 */
    private String fetchCsrfToken() {
        ResponseEntity<String> resp = rest.getForEntity("/console/api/auth/me", String.class);
        return cookieOf(resp, "XSRF-TOKEN");
    }

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

    /** 控制台登录（真实 CSRF 流）：领令牌 → POST login → 返回会话 Cookie。 */
    private String login(String username, String password) {
        String csrf = fetchCsrfToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CSRF-TOKEN", csrf);
        headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);
        ResponseEntity<String> resp = rest.exchange(
                "/console/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                        headers),
                String.class);
        assertThat(resp.getStatusCode().value()).as("登录应成功。body=" + resp.getBody()).isEqualTo(200);
        return cookieOf(resp, "SESSION");
    }

    private ResponseEntity<String> getWithSession(String path, String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** 走真实链路建一张模板（旧令牌 dev-admin-token，dev profile 便利值），返回自增 id。 */
    private long createTemplateViaApi(String name) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", "dev-admin-token");
        String body = "{\"name\":\"" + name + "\",\"activityId\":1001,\"couponType\":1,"
                + "\"faceValue\":100,\"threshold\":1000,\"totalCount\":10,\"perUserLimit\":1,"
                + "\"validType\":2,\"validDays\":7}";
        ResponseEntity<String> resp = rest.exchange("/api/templates", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value()).as("建模板应成功。body=" + resp.getBody()).isEqualTo(200);
        JsonNode data = JSON.readTree(resp.getBody()).path("data");
        assertThat(data.path("id").asLong()).isGreaterThan(0);
        return data.path("id").asLong();
    }

    // ------------------------------------------------------------------
    // ① 未认证拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① 匿名 GET /api/templates → 401 + 7002（列表不因控制台要人而开口子）")
    void anonymousGet_rejected() {
        ResponseEntity<String> resp = rest.getForEntity("/api/templates?page=1&size=20", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).contains("7002");
    }

    // ------------------------------------------------------------------
    // ② 会话登录后列表可用 + 权限码生效
    // ------------------------------------------------------------------

    @Test
    @DisplayName("② 登录后 GET 列表 200：total/rows 形状对、id 倒序；无角色用户 403")
    void loggedIn_listShapeOk() throws Exception {
        long idA = createTemplateViaApi("2D控制台列表A-" + UUID.randomUUID().toString().substring(0, 8));
        long idB = createTemplateViaApi("2D控制台列表B-" + UUID.randomUUID().toString().substring(0, 8));

        String session = login("admin", SEED_ADMIN_PASSWORD);
        ResponseEntity<String> resp =
                getWithSession("/api/templates?page=1&size=20", session);
        assertThat(resp.getStatusCode().value()).as("body=" + resp.getBody()).isEqualTo(200);

        JsonNode root = JSON.readTree(resp.getBody());
        assertThat(root.path("code").asInt()).isZero();
        JsonNode data = root.path("data");
        assertThat(data.has("total")).isTrue();
        assertThat(data.path("page").asInt()).isEqualTo(1);
        assertThat(data.path("size").asInt()).isEqualTo(20);
        assertThat(data.path("total").asLong()).as("两个新模板已入库").isGreaterThanOrEqualTo(2);

        JsonNode rows = data.path("rows");
        assertThat(rows.isArray()).isTrue();
        // 首页按 id 倒序：刚造的两张（全库最大 id）必须出现在第一页，且大 id 在前
        long firstId = rows.get(0).path("id").asLong();
        assertThat(firstId).isEqualTo(Math.max(idA, idB));
        JsonNode second = rows.get(1);
        assertThat(second.path("id").asLong()).isEqualTo(Math.min(idA, idB));
        // 行形状：控制台表格的八列逐一非空（createTime 由 DB 生成、列表 SQL 显式带出）
        assertThat(second.path("name").asText()).startsWith("2D控制台列表");
        assertThat(second.path("couponType").asText()).isEqualTo("满减");
        assertThat(second.path("faceValue").asLong()).isEqualTo(100);
        assertThat(second.path("publishStatus").asText()).isEqualTo("未发布");
        assertThat(second.path("totalCount").asLong()).isEqualTo(10);
        assertThat(second.path("issuedCount").asLong()).isZero();
        assertThat(second.path("createTime").isTextual()).isTrue();

        // template:read 在 @PreAuthorize 上真实生效：无角色用户有会话也 403
        com.newcoupon.infrastructure.persistence.po.AdminUserPO noRole = new com.newcoupon.infrastructure.persistence.po.AdminUserPO();
        noRole.setUsername("it-2d-norole-" + UUID.randomUUID().toString().substring(0, 8));
        noRole.setPasswordHash(passwordEncoder.encode(USER_PASSWORD));
        noRole.setDisplayName("2D无角色");
        noRole.setEnabled(1);
        noRole.setAuthVersion(0L);
        rbacMapper.insert(noRole);
        String noRoleSession = login(noRole.getUsername(), USER_PASSWORD);
        assertThat(getWithSession("/api/templates?page=1&size=20", noRoleSession).getStatusCode().value())
                .as("有会话但没有 template:read → 403（权限码不是摆设）")
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // ③ 分页边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("③ page 超界空 rows 不报错；page<1 / size>100 拒绝（1000）")
    void paginationBounds() throws Exception {
        String session = login("admin", SEED_ADMIN_PASSWORD);

        // 超界页：不报错、rows 空、total 照常（前端据此渲染"没有更多"）
        ResponseEntity<String> far = getWithSession("/api/templates?page=99999&size=20", session);
        assertThat(far.getStatusCode().value()).isEqualTo(200);
        JsonNode farData = JSON.readTree(far.getBody()).path("data");
        assertThat(farData.path("rows").isArray()).isTrue();
        assertThat(farData.path("rows").size()).isZero();
        assertThat(farData.path("total").asLong()).isGreaterThanOrEqualTo(0);

        // page<1：拒绝（page 从 1 起）
        ResponseEntity<String> page0 = getWithSession("/api/templates?page=0&size=20", session);
        assertThat(page0.getStatusCode().value()).isEqualTo(400);
        assertThat(JSON.readTree(page0.getBody()).path("code").asInt()).isEqualTo(1000);

        // size 超上限：拒绝（防一次拉全表）
        ResponseEntity<String> bigSize = getWithSession("/api/templates?page=1&size=101", session);
        assertThat(bigSize.getStatusCode().value()).isEqualTo(400);
        assertThat(JSON.readTree(bigSize.getBody()).path("code").asInt()).isEqualTo(1000);
    }

    // ------------------------------------------------------------------
    // ④ 控制台静态资源可服务（Vite 与降级静态版同判据）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("④ GET /console/ → 200 HTML，且引用的 js 真实存在（防产物丢的假绿）")
    void consoleStatic_servedWithAssets() throws Exception {
        ResponseEntity<String> page = rest.getForEntity("/console/", String.class);
        assertThat(page.getStatusCode().value())
                .as("控制台入口匿名可达（HTML/JS 无敏感数据，敏感全在 /console/api/** 与 /api/** 认证后面）")
                .isEqualTo(200);
        assertThat(String.valueOf(page.getHeaders().getContentType())).contains("html");
        assertThat(page.getBody()).contains("</html>");

        // 解析首个 script src（Vite 构建产物 /console/assets/index-*.js）再 GET 它——
        // index.html 在而构建产物丢是最常见的"部署了一半"事故
        String html = page.getBody();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<script[^>]+src=\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).as("入口 HTML 应引用至少一个脚本。html=" + html).isTrue();
        String src = m.group(1);
        ResponseEntity<String> js = rest.getForEntity(src, String.class);
        assertThat(js.getStatusCode().value())
                .as("HTML 引用的脚本必须真实存在：" + src).isEqualTo(200);
    }
}
