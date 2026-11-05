package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * V6 4A · 风控决策框架验收（开关开：coupon.switch.risk.decision.enabled=true）。
 *
 * <p>覆盖语义钉子（docs/runbook/风控最小版.md）：
 * <ol>
 *   <li>黑名单（用户/设备）→ 领券 7003（HTTP 403）；决策日志含因子快照/命中规则/traceId；</li>
 *   <li>白名单命中 → 同值黑名单也不拦（短路）；灰名单 → 放行 + REVIEW 日志；</li>
 *   <li>设备日限：同设备第 11 次（阈值 10）REJECT；无 X-Device-Id 跳过不挡（degraded）；</li>
 *   <li>名单过期/租户范围：过期不生效；别的租户的名单不拦我；本租户（内置 app '1'）拦；</li>
 *   <li>查询端点过滤与时间窗护栏；回放：规则未变→同结果，改名单→ruleDiff 可见；</li>
 *   <li>RBAC（匿名 401 / 无 risk:write 403）与审计留痕；名单管理幂等/校验。</li>
 * </ol>
 *
 * <p>数据纪律（共享容器）：每用例用 UUID 独有 userId/deviceId/requestId；名单条目
 * 独有 value——不与其它用例/历史运行互相污染。
 */
@Timeout(120)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "coupon.switch.risk.decision.enabled=true"
})
class RiskDecisionIntegrationTest extends ShardedIntegrationTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_TOKEN = "dev-admin-token";   // application-dev.yml 便利值
    private static final String USER_PASSWORD = "it-risk-pass-123";

    /** 专属租户（R6 租户范围用）：真实存在但不拥有内置模板——它的名单不该拦内置 app 的领券 */
    private static final String OTHER_APP = "t-risk-4a-other";

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate rest;
    @Autowired private com.newcoupon.application.service.OpenAppService appService;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired private com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper rbacMapper;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired private DataSource dataSource;

    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();
    private static volatile long templateId;

    @BeforeEach
    void provisionOnce() throws Exception {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        // 专属租户（R6 用）：真实存在（租户级名单的入口校验要求），但不拥有任何模板
        try {
            appService.create(new com.newcoupon.application.service.OpenAppService.CreateAppRequest(
                    OTHER_APP, "风控4A-他租户", null, null, null));
        } catch (Exception ignored) {
            // 重复运行（同一容器）时已存在——复用即可
        }
        // 内置面建模板并发布：内部面创建归属内置应用 app_id='1'（AppOwnership.ownerForNewResource）
        HttpHeaders headers = adminHeaders(null);
        String body = "{\"name\":\"风控4A验证券-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"activityId\":1001,\"couponType\":1,\"faceValue\":100,"
                + "\"threshold\":1000,\"totalCount\":500,\"perUserLimit\":50,"
                + "\"validType\":2,\"validDays\":7}";
        ResponseEntity<String> created = rest.exchange("/api/templates", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode().value()).as("建模板失败: " + created.getBody()).isEqualTo(200);
        templateId = JSON.readTree(created.getBody()).path("data").path("id").asLong();
        ResponseEntity<String> published = rest.exchange("/api/templates/" + templateId + "/publish",
                HttpMethod.POST, new HttpEntity<>("", headers), String.class);
        assertThat(published.getStatusCode().value()).as("发布模板失败: " + published.getBody()).isEqualTo(200);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // HTTP 小工具
    // ------------------------------------------------------------------

    private static HttpHeaders adminHeaders(String deviceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        if (deviceId != null) {
            headers.set("X-Device-Id", deviceId);
        }
        return headers;
    }

    /** 内部面领券（平台身份，模板=内置 app '1'）；deviceId 可空 */
    private ResponseEntity<String> receive(String requestId, long userId, String deviceId) {
        String body = "{\"requestId\":\"" + requestId + "\",\"userId\":" + userId
                + ",\"templateId\":" + templateId + "}";
        return rest.exchange("/api/coupons/receive", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders(deviceId)), String.class);
    }

    /** 增名单（管理端点走真实链路）；返回 data 节点（含 id） */
    private JsonNode addList(String listType, String valueType, String value,
                             String appId, String reason, String expiresAt) throws Exception {
        StringBuilder body = new StringBuilder("{\"listType\":\"" + listType
                + "\",\"valueType\":\"" + valueType + "\",\"value\":\"" + value
                + "\",\"reason\":\"" + reason + "\"");
        if (appId != null) {
            body.append(",\"appId\":\"").append(appId).append("\"");
        }
        if (expiresAt != null) {
            body.append(",\"expiresAt\":\"").append(expiresAt).append("\"");
        }
        body.append("}");
        ResponseEntity<String> resp = rest.exchange("/api/risk/lists", HttpMethod.POST,
                new HttpEntity<>(body.toString(), adminHeaders(null)), String.class);
        assertThat(resp.getStatusCode().value()).as("增名单失败: " + resp.getBody()).isEqualTo(200);
        JsonNode root = JSON.readTree(resp.getBody());
        assertThat(root.path("code").asInt()).as("增名单业务码: " + resp.getBody()).isZero();
        return root.path("data");
    }

    private static int codeOf(ResponseEntity<String> resp) throws Exception {
        return JSON.readTree(resp.getBody()).path("code").asInt();
    }

    private static long uniqueUserId() {
        return 96_000_000L + (System.nanoTime() % 1_000_000L);
    }

    // ------------------------------------------------------------------
    // ① 黑名单（用户/设备）→ 7003；决策日志可解释
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R1: 拉黑 userId → 领券 HTTP 403 + 7003；日志行含 factors/matched_rules/request_id")
    void blacklistUserRejectedWithExplainableLog() throws Exception {
        long userId = uniqueUserId();
        addList("BLACK", "USER_ID", String.valueOf(userId), null, "R1 拉黑-脚本刷券", null);

        ResponseEntity<String> resp = receive("rk-r1-" + UUID.randomUUID(), userId, null);
        assertThat(resp.getStatusCode().value())
                .as("风控拒绝是 HTTP 层语义（与 6011 同族 403）。body=" + resp.getBody()).isEqualTo(403);
        assertThat(codeOf(resp)).isEqualTo(7003);
        // 文案不泄漏命中了哪条规则——教攻击者换维度等于白拦
        assertThat(resp.getBody()).doesNotContain("R_LIST_BLACK").doesNotContain("黑名单");

        // 决策日志：可解释性三件套（因子快照/命中规则/幂等键）
        var rows = jdbc().queryForMap(
                "SELECT action, matched_rules, factors_json, request_id, trace_id, degraded "
                        + "FROM risk_decision_log WHERE user_id = ? AND scene = 'RECEIVE' "
                        + "ORDER BY id DESC LIMIT 1", userId);
        assertThat(rows.get("action")).isEqualTo("REJECT");
        assertThat((String) rows.get("matched_rules")).contains("R_LIST_BLACK");
        assertThat((String) rows.get("factors_json")).contains("list.user").contains("BLACK");
        assertThat(rows.get("request_id")).asString().startsWith("rk-r1-");
        assertThat(rows.get("trace_id")).asString().isNotEmpty();   // 排障入口：与响应头 X-Trace-Id 同源
        assertThat(((Number) rows.get("degraded")).intValue()).isZero();
    }

    @Test
    @DisplayName("R2: 拉黑设备 → 带 X-Device-Id 领券 7003；不带头=设备因子缺失放行（degraded=1）")
    void blacklistDeviceRejected_headerOptional() throws Exception {
        String device = "DEV-R2-" + UUID.randomUUID();
        addList("BLACK", "DEVICE_ID", device, null, "R2 拉黑-多账号同设备", null);
        long userId = uniqueUserId();

        ResponseEntity<String> rejected = receive("rk-r2a-" + UUID.randomUUID(), userId, device);
        assertThat(rejected.getStatusCode().value()).isEqualTo(403);
        assertThat(codeOf(rejected)).isEqualTo(7003);
        assertThat(jdbc().queryForObject(
                "SELECT factors_json FROM risk_decision_log WHERE device_id = ? "
                        + "AND action = 'REJECT' ORDER BY id DESC LIMIT 1", String.class, device))
                .contains("list.device").contains("BLACK");

        // 不带头：同一用户照常领到（设备维度缺失≠攻击——接入方没接设备头不能全员被拒）
        long user2 = uniqueUserId();
        ResponseEntity<String> allowed = receive("rk-r2b-" + UUID.randomUUID(), user2, null);
        assertThat(allowed.getStatusCode().value()).as("body=" + allowed.getBody()).isEqualTo(200);
        assertThat(codeOf(allowed)).isZero();
        Integer degraded = jdbc().queryForObject(
                "SELECT degraded FROM risk_decision_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                (rs, n) -> ((Number) rs.getObject(1)).intValue(), user2);
        assertThat(degraded).as("缺设备头=因子不完整，degraded=1 且放行").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ② 白名单短路 / 灰名单 REVIEW
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R3: 白名单命中 → 同值黑名单也不拦（白优先短路）；日志 action=PASS 且两行名单可见")
    void whitelistShortCircuitsBlacklist() throws Exception {
        long userId = uniqueUserId();
        addList("BLACK", "USER_ID", String.valueOf(userId), null, "R3 先拉黑", null);
        addList("WHITE", "USER_ID", String.valueOf(userId), null, "R3 运营复核后放行", null);

        ResponseEntity<String> resp = receive("rk-r3-" + UUID.randomUUID(), userId, null);
        assertThat(resp.getStatusCode().value()).as("白名单是运营的明确意思表示。body=" + resp.getBody())
                .isEqualTo(200);
        assertThat(codeOf(resp)).isZero();

        var rows = jdbc().queryForMap(
                "SELECT action, matched_rules, factors_json FROM risk_decision_log "
                        + "WHERE user_id = ? ORDER BY id DESC LIMIT 1", userId);
        assertThat(rows.get("action")).isEqualTo("PASS");
        assertThat((String) rows.get("matched_rules")).contains("R_LIST_BLACK");   // 规则命中了（产出 PASS）
        assertThat((String) rows.get("factors_json")).contains("BLACK").contains("WHITE");
    }

    @Test
    @DisplayName("R4: 灰名单 → 放行 + REVIEW 日志（留痕等人工，不打扰用户）")
    void grayListPassesWithReviewLog() throws Exception {
        long userId = uniqueUserId();
        addList("GRAY", "USER_ID", String.valueOf(userId), null, "R4 疑似-观察名单", null);

        ResponseEntity<String> resp = receive("rk-r4-" + UUID.randomUUID(), userId, null);
        assertThat(resp.getStatusCode().value()).as("REVIEW 不拦用户。body=" + resp.getBody()).isEqualTo(200);
        assertThat(codeOf(resp)).isZero();
        assertThat(jdbc().queryForObject(
                "SELECT action FROM risk_decision_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, userId)).isEqualTo("REVIEW");
    }

    // ------------------------------------------------------------------
    // ③ 名单过期 / 租户范围
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R5: 过期名单命中不生效（查询侧过滤，行保留=申诉现场）")
    void expiredEntryIgnored() throws Exception {
        long userId = uniqueUserId();
        // 服务入口拒绝"建出来就过期"的条目——直插模拟"拉黑后时间流逝过期"
        jdbc().update("INSERT INTO risk_list_entry "
                        + "(id, list_type, value_type, value, app_id, reason, expires_at, status, created_by) "
                        + "VALUES (?, 'BLACK', 'USER_ID', ?, '', 'R5 已过期', ?, 'ACTIVE', 'it')",
                System.nanoTime(), String.valueOf(userId), LocalDateTime.now().minusHours(1));

        ResponseEntity<String> resp = receive("rk-r5-" + UUID.randomUUID(), userId, null);
        assertThat(resp.getStatusCode().value()).as("过期即失效。body=" + resp.getBody()).isEqualTo(200);
        assertThat(codeOf(resp)).isZero();
        assertThat(jdbc().queryForObject(
                "SELECT factors_json FROM risk_decision_log WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT 1", String.class, userId))
                .contains("\"list.user\":\"none\"");
    }

    @Test
    @DisplayName("R6: 租户范围——他租户的名单不拦内置 app 的领券；本租户(app '1')的拦")
    void listScopedToApp() throws Exception {
        long userIdA = uniqueUserId();
        addList("BLACK", "USER_ID", String.valueOf(userIdA), OTHER_APP, "R6 他租户黑名单", null);
        ResponseEntity<String> otherApp = receive("rk-r6a-" + UUID.randomUUID(), userIdA, null);
        assertThat(otherApp.getStatusCode().value())
                .as("名单跟着券的归属租户走，他租户的名单不该拦内置 app。body=" + otherApp.getBody())
                .isEqualTo(200);
        assertThat(codeOf(otherApp)).isZero();

        long userIdB = uniqueUserId();
        addList("BLACK", "USER_ID", String.valueOf(userIdB), "1", "R6 本租户黑名单", null);
        ResponseEntity<String> ownApp = receive("rk-r6b-" + UUID.randomUUID(), userIdB, null);
        assertThat(ownApp.getStatusCode().value()).isEqualTo(403);
        assertThat(codeOf(ownApp)).isEqualTo(7003);
    }

    // ------------------------------------------------------------------
    // ④ 设备日限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R7: 同设备第 11 次（阈值 10）→ 7003 命中 R_DEVICE_DAILY；换设备重新计；Redis 键带 TTL")
    void deviceDailyLimitRejectedOn11th() throws Exception {
        String device = "DEV-R7-" + UUID.randomUUID();
        long userId = uniqueUserId();
        for (int i = 1; i <= 10; i++) {
            ResponseEntity<String> resp = receive("rk-r7-" + i + "-" + UUID.randomUUID(), userId, device);
            assertThat(resp.getStatusCode().value())
                    .as("第 " + i + " 次应放行。body=" + resp.getBody()).isEqualTo(200);
            assertThat(codeOf(resp)).isZero();
        }
        ResponseEntity<String> eleventh = receive("rk-r7-11-" + UUID.randomUUID(), userId, device);
        assertThat(eleventh.getStatusCode().value())
                .as("计数含被拒尝试：第 11 次超阈。body=" + eleventh.getBody()).isEqualTo(403);
        assertThat(codeOf(eleventh)).isEqualTo(7003);
        assertThat(jdbc().queryForObject(
                "SELECT matched_rules FROM risk_decision_log WHERE device_id = ? "
                        + "AND action = 'REJECT' ORDER BY id DESC LIMIT 1", String.class, device))
                .contains("R_DEVICE_DAILY");

        // 换设备头=新计数桶（透传维度的现实：换头绕过——商用指纹是升级路径）
        ResponseEntity<String> otherDevice = receive("rk-r7-other-" + UUID.randomUUID(),
                userId, device + "-x");
        assertThat(otherDevice.getStatusCode().value()).isEqualTo(200);

        // 键形态与 TTL：运维按 c:risk:dev:* 排查的习惯成立；无残留
        var keys = redis.keys("c:risk:dev:1:" + device + ":*");
        assertThat(keys).as("设备日限键应存在（appId=1=内置模板归属）").isNotEmpty();
        assertThat(redis.getExpire(keys.iterator().next())).isGreaterThan(0L);
    }

    // ------------------------------------------------------------------
    // ⑤ 决策查询端点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R8: decisions/query 按 scene/action/appId 过滤命中；时间窗护栏（不成对/超 31 天/拼错枚举=1000）")
    void decisionQueryFiltersAndGuards() throws Exception {
        long userId = uniqueUserId();
        addList("BLACK", "USER_ID", String.valueOf(userId), null, "R8 查询用", null);
        String requestId = "rk-r8-" + UUID.randomUUID();
        assertThat(codeOf(receive(requestId, userId, null))).isEqualTo(7003);

        ResponseEntity<String> query = rest.exchange("/api/risk/decisions/query", HttpMethod.POST,
                new HttpEntity<>("{\"scene\":\"RECEIVE\",\"action\":\"REJECT\",\"appId\":\"1\",\"limit\":50}",
                        adminHeaders(null)), String.class);
        assertThat(query.getStatusCode().value()).isEqualTo(200);
        assertThat(query.getBody()).as("按过滤条件应找到 R8 的拒绝行").contains(requestId);

        // 护栏三连：只配一头 / 窗口超长 / 枚举拼错——都拒绝而不是静默空列表
        assertThat(codeOf(rest.exchange("/api/risk/decisions/query", HttpMethod.POST,
                new HttpEntity<>("{\"startTime\":\"2026-11-01T00:00:00\"}", adminHeaders(null)),
                String.class))).isEqualTo(1000);
        assertThat(codeOf(rest.exchange("/api/risk/decisions/query", HttpMethod.POST,
                new HttpEntity<>("{\"startTime\":\"2026-10-01T00:00:00\",\"endTime\":\"2026-11-05T00:00:00\"}",
                        adminHeaders(null)), String.class))).isEqualTo(1000);
        assertThat(codeOf(rest.exchange("/api/risk/decisions/query", HttpMethod.POST,
                new HttpEntity<>("{\"action\":\"REJECTE\"}", adminHeaders(null)), String.class)))
                .isEqualTo(1000);
    }

    // ------------------------------------------------------------------
    // ⑥ 回放（只读；改名单后 ruleDiff 可见——回放的真实价值）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R9: 回放——规则未变同结果；停用名单后重放 PASS 且 ruleDiff/动作差异可见；原行不改、不写新行")
    void replayComparesCurrentRules() throws Exception {
        long userId = uniqueUserId();
        JsonNode entry = addList("BLACK", "USER_ID", String.valueOf(userId), null, "R9 回放用", null);
        long entryId = entry.path("id").asLong();
        String requestId = "rk-r9-" + UUID.randomUUID();
        assertThat(codeOf(receive(requestId, userId, null))).isEqualTo(7003);
        Long logId = jdbc().queryForObject(
                "SELECT id FROM risk_decision_log WHERE user_id = ? AND request_id = ?",
                Long.class, userId, requestId);
        Integer totalRows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log WHERE user_id = ?", Integer.class, userId);

        // 第一次回放：名单还在、规则未变 → 同结果；设备日限因副作用被跳过（如实列出）
        ResponseEntity<String> same = rest.exchange("/api/risk/decisions/" + logId + "/replay",
                HttpMethod.POST, new HttpEntity<>(adminHeaders(null)), String.class);
        assertThat(same.getStatusCode().value()).isEqualTo(200);
        JsonNode sameData = JSON.readTree(same.getBody()).path("data");
        assertThat(sameData.path("replayAction").asText()).isEqualTo("REJECT");
        assertThat(sameData.path("ruleDiff").toString()).as("规则未变=无差异").isEqualTo("[]");
        assertThat(sameData.path("skippedRules").toString()).contains("R_DEVICE_DAILY");

        // 停用名单（误杀复盘的常见动作：先放人再查）→ 重放变 PASS，diff 把原因说清
        rest.exchange("/api/risk/lists/disable", HttpMethod.POST,
                new HttpEntity<>("{\"id\":" + entryId + "}", adminHeaders(null)), String.class);
        ResponseEntity<String> changed = rest.exchange("/api/risk/decisions/" + logId + "/replay",
                HttpMethod.POST, new HttpEntity<>(adminHeaders(null)), String.class);
        JsonNode changedData = JSON.readTree(changed.getBody()).path("data");
        assertThat(changedData.path("originalAction").asText()).isEqualTo("REJECT");
        assertThat(changedData.path("replayAction").asText())
                .as("名单已停用：现行规则集不会再拦他").isEqualTo("PASS");
        assertThat(changedData.path("ruleDiff").toString())
                .contains("R_LIST_BLACK").contains("action");

        // 只读语义：原行 action 不被改写；不写新行
        assertThat(jdbc().queryForObject(
                "SELECT action FROM risk_decision_log WHERE id = ?", String.class, logId))
                .isEqualTo("REJECT");
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(totalRows);
    }

    // ------------------------------------------------------------------
    // ⑦ RBAC + 审计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R10: 匿名 401；无 risk:write 会话 403；建名单留审计行（query/replay 不记）")
    void rbacAndAudit() throws Exception {
        HttpHeaders anon = new HttpHeaders();
        anon.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange("/api/risk/lists", HttpMethod.POST,
                new HttpEntity<>("{}", anon), String.class).getStatusCode().value()).isEqualTo(401);

        // 无角色会话：认证过但没有 risk:write → 403（权限码不是摆设）
        com.newcoupon.infrastructure.persistence.po.AdminUserPO noRole =
                new com.newcoupon.infrastructure.persistence.po.AdminUserPO();
        noRole.setUsername("it-risk-norole-" + UUID.randomUUID().toString().substring(0, 8));
        noRole.setPasswordHash(passwordEncoder.encode(USER_PASSWORD));
        noRole.setDisplayName("risk无角色");
        noRole.setEnabled(1);
        noRole.setAuthVersion(0L);
        rbacMapper.insert(noRole);
        String session = login(noRole.getUsername(), USER_PASSWORD);
        HttpHeaders sessionHeaders = new HttpHeaders();
        sessionHeaders.setContentType(MediaType.APPLICATION_JSON);
        sessionHeaders.set(HttpHeaders.COOKIE, "SESSION=" + session);
        assertThat(rest.exchange("/api/risk/lists", HttpMethod.POST,
                new HttpEntity<>("{\"listType\":\"BLACK\",\"valueType\":\"IP\","
                        + "\"value\":\"9.9.9.9\",\"reason\":\"应被 403\"}", sessionHeaders),
                String.class).getStatusCode().value()).isEqualTo(403);

        // 审计：写动作留痕（异步落库，轮询等待）
        JsonNode entry = addList("BLACK", "IP", "10.9.9." + (System.nanoTime() % 250),
                null, "R10 审计用", null);
        assertThat(awaitAudit("RISK_LIST_ADD", String.valueOf(entry.path("id").asLong()))).isTrue();
    }

    // ------------------------------------------------------------------
    // ⑧ 名单管理：幂等/校验/停用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R11: 同自然键重放 duplicated=true 不建新行；枚举/过期方向/租户不存在拒绝；停用幂等")
    void listManagementIdempotencyAndValidation() throws Exception {
        long userId = uniqueUserId();
        String value = String.valueOf(userId);
        JsonNode first = addList("BLACK", "USER_ID", value, null, "R11 幂等", null);
        assertThat(first.path("duplicated").asBoolean()).isFalse();
        JsonNode replayed = addList("BLACK", "USER_ID", value, null, "R11 幂等（重放）", null);
        assertThat(replayed.path("duplicated").asBoolean()).isTrue();
        assertThat(replayed.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_list_entry WHERE value = ?", Integer.class, value)).isEqualTo(1);

        // 枚举拼错：1000（落库后永远命不中的条目在入口就拒）
        assertThat(codeOf(rest.exchange("/api/risk/lists", HttpMethod.POST,
                new HttpEntity<>("{\"listType\":\"BLOCK\",\"valueType\":\"IP\","
                        + "\"value\":\"1.2.3.4\",\"reason\":\"拼错\"}", adminHeaders(null)),
                String.class))).isEqualTo(1000);
        // 过期方向：建出来就过期没有意义
        assertThat(codeOf(rest.exchange("/api/risk/lists", HttpMethod.POST,
                new HttpEntity<>("{\"listType\":\"BLACK\",\"valueType\":\"IP\",\"value\":\"1.2.3.5\","
                        + "\"reason\":\"已过期\",\"expiresAt\":\"2020-01-01T00:00:00\"}",
                        adminHeaders(null)), String.class))).isEqualTo(1000);
        // 租户不存在：名单挂到拼写错误的租户上永远命不中
        assertThat(codeOf(rest.exchange("/api/risk/lists", HttpMethod.POST,
                new HttpEntity<>("{\"listType\":\"BLACK\",\"valueType\":\"IP\",\"value\":\"1.2.3.6\","
                        + "\"appId\":\"no-such-risk-app\",\"reason\":\"租户不存在\"}", adminHeaders(null)),
                String.class))).isEqualTo(1002);

        // 停用：两次调用第二次 duplicated=true；不存在的 id 1002
        Long id = first.path("id").asLong();
        ResponseEntity<String> disable = rest.exchange("/api/risk/lists/disable", HttpMethod.POST,
                new HttpEntity<>("{\"id\":" + id + "}", adminHeaders(null)), String.class);
        assertThat(JSON.readTree(disable.getBody()).path("data").path("status").asText())
                .isEqualTo("DISABLED");
        ResponseEntity<String> disableAgain = rest.exchange("/api/risk/lists/disable", HttpMethod.POST,
                new HttpEntity<>("{\"id\":" + id + "}", adminHeaders(null)), String.class);
        assertThat(JSON.readTree(disableAgain.getBody()).path("data").path("duplicated").asBoolean())
                .isTrue();
        assertThat(codeOf(rest.exchange("/api/risk/lists/disable", HttpMethod.POST,
                new HttpEntity<>("{\"id\":-999}", adminHeaders(null)), String.class))).isEqualTo(1002);

        // 停用后命中失效（软删行还在=申诉现场）
        ResponseEntity<String> afterDisable = receive("rk-r11-" + UUID.randomUUID(), userId, null);
        assertThat(afterDisable.getStatusCode().value()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // 控制台会话小工具（SettlementHoldIntegrationTest 同款）
    // ------------------------------------------------------------------

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

    private String login(String username, String password) {
        ResponseEntity<String> csrfResp = rest.getForEntity("/console/api/auth/me", String.class);
        String csrf = cookieOf(csrfResp, "XSRF-TOKEN");
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

    /** 轮询等该动作的异步审计行出现（最多 3 秒；审计可丢语义下测试容错） */
    private boolean awaitAudit(String action, String resourceId) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Integer count = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_id = ?",
                    Integer.class, action, resourceId);
            if (count != null && count >= 1) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
