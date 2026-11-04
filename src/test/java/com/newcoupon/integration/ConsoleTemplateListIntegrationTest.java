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
 *       防"index.html 在、构建产物丢"的假绿（Vite 与降级静态版同判据）；</li>
 *   <li><b>会话 Cookie 属性</b>（审查 Ma1）：登录下发的 SESSION 带 SameSite=Lax——
 *       /api 也认会话（ambient Cookie），跨站携带必须挡。</li>
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

    @Autowired
    private com.newcoupon.application.service.TemplateAppService templateAppService;

    @Autowired
    private com.newcoupon.application.service.ReceiveAppService receiveAppService;

    @Autowired
    private com.newcoupon.infrastructure.persistence.mapper.TemplateMapper templateMapper;

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

    /** 从 rows 里按 id 找行（测试造的两张必在第一页）。 */
    private static JsonNode findRow(JsonNode rows, long id) {
        for (JsonNode row : rows) {
            if (row.path("id").asLong() == id) {
                return row;
            }
        }
        throw new AssertionError("rows 里没有 id=" + id);
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
    @DisplayName("② 登录后 GET 列表 200：total/rows 形状对、id 倒序、已领=分片真实口径；无角色 403")
    void loggedIn_listShapeOk() throws Exception {
        long idA = createTemplateViaApi("2D控制台列表A-" + UUID.randomUUID().toString().substring(0, 8));
        long idB = createTemplateViaApi("2D控制台列表B-" + UUID.randomUUID().toString().substring(0, 8));

        // idA 走完整发券链（建→发布→领一张）：列表"已领"必须是分片真实口径 1
        // （coupon_template.issued_count 是 V10 冻结快照、C1 下恒 0——ADR-0018 §3.1）
        templateAppService.publish(idA);
        long userId = 70_000 + (System.currentTimeMillis() % 10_000);
        receiveAppService.receive(
                new com.newcoupon.application.dto.ReceiveRequest("2d-list-" + userId, userId, idA));

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
        assertThat(rows.get(0).path("id").asLong()).isEqualTo(Math.max(idA, idB));
        JsonNode untouched = findRow(rows, Math.max(idA, idB));   // 未动过的那张（造后没发布）
        JsonNode issued = findRow(rows, Math.min(idA, idB));      // 发了一张的那张（idA 先建 id 更小）
        // 行形状：控制台表格的八列逐一非空（createTime 由 DB 生成、列表 SQL 显式带出）
        assertThat(untouched.path("name").asText()).startsWith("2D控制台列表");
        assertThat(untouched.path("couponType").asText()).isEqualTo("满减");
        assertThat(untouched.path("faceValue").asLong()).isEqualTo(100);
        assertThat(untouched.path("publishStatus").asText()).isEqualTo("未发布");
        assertThat(untouched.path("totalCount").asLong()).isEqualTo(10);
        assertThat(untouched.path("issuedCount").asLong()).isZero();
        assertThat(untouched.path("createTime").isTextual()).isTrue();
        // idA：发了一张 → 已领=1（真实口径 SUM(coupon_stock_shard.used)；冻结列会给 0——
        // 这条断言在改口径前就是红的，先红后绿的取证点）
        assertThat(issued.path("publishStatus").asText()).isEqualTo("已发布");
        assertThat(issued.path("issuedCount").asLong())
                .as("已领列=分片真实口径（发过 1 张），不是 V10 冻结快照列")
                .isEqualTo(1);

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
    // ④' 会话 Cookie 属性（审查 Ma1）：SESSION 是 ambient 凭证且 /api 也认它，
    //    跨站携带必须被 SameSite=Lax 挡住（CSRF 令牌只护 /console/api/**）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("④' 登录下发的 SESSION Cookie 带 SameSite=Lax（/api 会话凭证的跨站防线）")
    void loginSessionCookie_sameSiteLax() {
        String csrf = fetchCsrfToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CSRF-TOKEN", csrf);
        headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);
        ResponseEntity<String> resp = rest.exchange(
                "/console/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"" + SEED_ADMIN_PASSWORD + "\"}",
                        headers),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).as("登录应下发会话 Cookie").isNotNull();
        String sessionCookie = setCookies.stream()
                .filter(c -> c.startsWith("SESSION="))
                .findFirst().orElseThrow();
        assertThat(sessionCookie)
                .as("SameSite=Lax 是 /api 会话凭证的跨站防线（跨站 POST 不带 Cookie），"
                        + "缺了它 /api 的无 JSON 体端点可被跨站表单打。cookie=" + sessionCookie)
                .contains("SameSite=Lax");
    }

    // ------------------------------------------------------------------
    // ④ 控制台静态资源可服务（Vite 与降级静态版同判据）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("④ GET /console/ → 200 HTML，且引用的 js/css 资产全部真实存在（防产物丢的假绿）")
    void consoleStatic_servedWithAssets() throws Exception {
        ResponseEntity<String> page = rest.getForEntity("/console/", String.class);
        assertThat(page.getStatusCode().value())
                .as("控制台入口匿名可达（HTML/JS 无敏感数据，敏感全在 /console/api/** 与 /api/** 认证后面）")
                .isEqualTo(200);
        assertThat(String.valueOf(page.getHeaders().getContentType())).contains("html");
        assertThat(page.getBody()).contains("</html>");

        // 解析 HTML 引用的全部资产再逐个 GET——script 与 link 一视同仁（审查 mi5：
        // 只验 script 时 css 同族事故漏网）；路径从 HTML 动态解析，不硬编码 hash 文件名。
        // index.html 在而构建产物丢是最常见的"部署了一半"事故
        String html = page.getBody();
        List<String> assets = new java.util.ArrayList<>();
        java.util.regex.Matcher scripts = java.util.regex.Pattern
                .compile("<script[^>]+src=\"([^\"]+)\"").matcher(html);
        while (scripts.find()) {
            assets.add(scripts.group(1));
        }
        java.util.regex.Matcher links = java.util.regex.Pattern
                .compile("<link[^>]+href=\"([^\"]+)\"").matcher(html);
        while (links.find()) {
            assets.add(links.group(1));
        }
        assertThat(assets).as("入口 HTML 至少应引用一个脚本。html=" + html).isNotEmpty();
        for (String src : assets) {
            // 防御外链（favicon 站点等绝对 URL 不会出现在 vite 产物里，但断言只对同源资产负责）
            if (!src.startsWith("/")) {
                continue;
            }
            ResponseEntity<String> asset = rest.getForEntity(src, String.class);
            assertThat(asset.getStatusCode().value())
                    .as("HTML 引用的资产必须真实存在：" + src).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------
    // ⑤ 脏枚举行降级（审查 mi4）：列表是扇出读，一行脏数据不掀翻整页
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑤ 某行 coupon_type 为未知码 → 列表 200 且该行显示'未知码'，不 500")
    void dirtyEnumRow_degradesNotWholePage500() throws Exception {
        long id = createTemplateViaApi("2D脏码行-" + UUID.randomUUID().toString().substring(0, 8));
        // 模拟版本回滚场景：库里出现当前代码不认识的枚举值（直接改列，绕过领域校验）
        templateMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.newcoupon.infrastructure.persistence.po.CouponTemplatePO>()
                        .eq(com.newcoupon.infrastructure.persistence.po.CouponTemplatePO::getId, id)
                        .set(com.newcoupon.infrastructure.persistence.po.CouponTemplatePO::getCouponType, 99));

        String session = login("admin", SEED_ADMIN_PASSWORD);
        ResponseEntity<String> resp = getWithSession("/api/templates?page=1&size=20", session);
        assertThat(resp.getStatusCode().value())
                .as("扇出读里一行脏数据不得变成整页 500（新模板 id 必在第一页，翻页绕不过）")
                .isEqualTo(200);
        JsonNode row = findRow(JSON.readTree(resp.getBody()).path("data").path("rows"), id);
        assertThat(row.path("couponType").asText())
                .as("未知码降级显示原始码，不抛不吞")
                .isEqualTo("券类型未知码 99");
        // 详情路径维持"抛错暴露不一致"的现状语义（见 TemplateQueryService.enumDesc 注释）
    }
}
