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
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 3min · settlement_hold 服务与端点验收（4B 风控挂点）。
 *
 * <p>端点全部 POST（内部面），写动作 settlement:write / 查询 settlement:read（V44 既有种子，
 * 无需补种）。建/释/作废每端点挂 AdminActionAuditor 留痕（2C 模式）；query 不记。
 *
 * <p>语义钉子：
 * <ol>
 *   <li>建：app 必须真实存在；amount&gt;0；reason 必填；holdNo 幂等（同号重放返回已存在）；</li>
 *   <li>状态机：ACTIVE→RELEASED / ACTIVE→VOIDED 两个终态出边；终态重放幂等成功；
 *       RELEASED↔VOIDED 互斥拒绝；</li>
 *   <li>RBAC：匿名 401；有会话无 settlement:write 403（权限码不是摆设）；</li>
 *   <li>审计：建/释/作废各留一行 audit_log（query 不留）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "coupon.security.login.ip-limit=1000"
})
class SettlementHoldIntegrationTest extends ShardedIntegrationTestSupport {

    private static final String SEED_ADMIN_PASSWORD = "dev-seed-admin-123";   // application-dev.yml
    private static final String USER_PASSWORD = "it-pass-123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate rest;
    @Autowired private com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper rbacMapper;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired private com.newcoupon.application.service.SettlementHoldService holdService;
    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // HTTP 小工具（ConsoleTemplateListIntegrationTest 同款）
    // ------------------------------------------------------------------

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", "dev-admin-token");
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
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

    private ResponseEntity<String> postWithSession(String path, String body, String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, "SESSION=" + session);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String holdBody(String holdNo, String appId, String party, Long templateId,
                            long amount, String reason) {
        return "{\"holdNo\":\"" + holdNo + "\",\"appId\":\"" + appId + "\",\"settlePartyId\":\"" + party
                + "\",\"templateId\":" + templateId + ",\"amount\":" + amount
                + ",\"reason\":\"" + reason + "\"}";
    }

    // ------------------------------------------------------------------
    // ① 建 + 幂等 + 审计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H1: 建冻结 200 落库 ACTIVE；同号重放返回已存在不建新行；审计行存在")
    void placeCreatesActiveHold_andIdempotentReplay() throws Exception {
        String holdNo = "HOLD-H1-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> resp = post("/api/settlement/holds",
                holdBody(holdNo, "1", "M_PARTY", null, 500, "人工冻结-测试"));
        assertThat(resp.getStatusCode().value()).as("body=" + resp.getBody()).isEqualTo(200);
        JsonNode data = JSON.readTree(resp.getBody()).path("data");
        assertThat(data.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.path("duplicated").asBoolean()).isFalse();

        String status = jdbc().queryForObject(
                "SELECT status FROM coupon_settlement_hold WHERE hold_no = ?", String.class, holdNo);
        assertThat(status).isEqualTo("ACTIVE");

        // 同号重放：返回已存在的那条（duplicated=true），不新建行
        ResponseEntity<String> replay = post("/api/settlement/holds",
                holdBody(holdNo, "1", "M_PARTY", null, 500, "人工冻结-测试"));
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        JsonNode replayData = JSON.readTree(replay.getBody()).path("data");
        assertThat(replayData.path("duplicated").asBoolean()).isTrue();
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_settlement_hold WHERE hold_no = ?",
                Integer.class, holdNo)).isEqualTo(1);

        // 审计留痕：建动作一行（query 不记——见 H6）；审计写入异步，轮询等落地
        assertThat(awaitAuditCount("SETTLEMENT_HOLD_CREATE", holdNo))
                .as("建冻结必须留管理面审计行").isTrue();
    }

    // ------------------------------------------------------------------
    // ② 参数/存在性校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H2: 金额非正/app 不存在/缺 reason 拒绝；模板不存在拒绝（悬空 hold 永远命不中账单）")
    void placeValidations() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        assertThat(post("/api/settlement/holds",
                holdBody("HOLD-H2A-" + suffix, "1", "M", null, 0, "零额")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(JSON.readTree(post("/api/settlement/holds",
                        holdBody("HOLD-H2A-" + suffix, "1", "M", null, 0, "零额")).getBody())
                .path("code").asInt()).isEqualTo(1000);

        // app 必须真实存在（防把 hold 挂到拼写错误的租户上——账单永远命不中）
        ResponseEntity<String> unknownApp = post("/api/settlement/holds",
                holdBody("HOLD-H2B-" + suffix, "no-such-app", "M", null, 100, "租户不存在"));
        assertThat(unknownApp.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(unknownApp.getBody()).path("code").asInt()).isEqualTo(1002);

        // reason 必填（@Valid 拦截）
        assertThat(post("/api/settlement/holds",
                "{\"holdNo\":\"HOLD-H2C-" + suffix + "\",\"appId\":\"1\","
                        + "\"settlePartyId\":\"M\",\"amount\":100,\"reason\":\"\"}")
                .getStatusCode().value()).isEqualTo(400);

        // 模板级 hold 的模板必须存在
        ResponseEntity<String> orphanTpl = post("/api/settlement/holds",
                holdBody("HOLD-H2D-" + suffix, "1", "M", -999L, 100, "模板不存在"));
        assertThat(orphanTpl.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(orphanTpl.getBody()).path("code").asInt()).isEqualTo(1002);
    }

    // ------------------------------------------------------------------
    // ③ 状态机：release / void + 幂等 + 非法迁移
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H3: release ACTIVE→RELEASED；重放幂等成功；RELEASED 后 void 拒绝（终态互斥）")
    void releaseLifecycle() throws Exception {
        String holdNo = "HOLD-H3-" + UUID.randomUUID().toString().substring(0, 8);
        post("/api/settlement/holds", holdBody(holdNo, "1", "M", null, 300, "释放流-测试"));

        ResponseEntity<String> release = post("/api/settlement/holds/release",
                "{\"holdNo\":\"" + holdNo + "\"}");
        assertThat(release.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(release.getBody()).path("data").path("status").asText())
                .isEqualTo("RELEASED");

        // 重放：已 RELEASED → 幂等成功返回原状态（迟到重试不是错误）
        ResponseEntity<String> replay = post("/api/settlement/holds/release",
                "{\"holdNo\":\"" + holdNo + "\"}");
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(replay.getBody()).path("data").path("status").asText())
                .isEqualTo("RELEASED");

        // 终态互斥：RELEASED → VOIDED 拒绝（4002）
        ResponseEntity<String> voidAfterRelease = post("/api/settlement/holds/void",
                "{\"holdNo\":\"" + holdNo + "\"}");
        assertThat(voidAfterRelease.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(voidAfterRelease.getBody()).path("code").asInt()).isEqualTo(4002);

        // 释放留审计（异步写入，轮询等落地）
        assertThat(awaitAuditCount("SETTLEMENT_HOLD_RELEASE", holdNo)).isTrue();
    }

    @Test
    @DisplayName("H4: void ACTIVE→VOIDED；重放幂等；VOIDED 后 release 拒绝；void 留审计")
    void voidLifecycle() throws Exception {
        String holdNo = "HOLD-H4-" + UUID.randomUUID().toString().substring(0, 8);
        post("/api/settlement/holds", holdBody(holdNo, "1", "M", null, 300, "作废流-测试"));

        ResponseEntity<String> voidResp = post("/api/settlement/holds/void",
                "{\"holdNo\":\"" + holdNo + "\"}");
        assertThat(voidResp.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(voidResp.getBody()).path("data").path("status").asText())
                .isEqualTo("VOIDED");

        assertThat(post("/api/settlement/holds/void", "{\"holdNo\":\"" + holdNo + "\"}")
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> releaseAfterVoid = post("/api/settlement/holds/release",
                "{\"holdNo\":\"" + holdNo + "\"}");
        assertThat(releaseAfterVoid.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(releaseAfterVoid.getBody()).path("code").asInt()).isEqualTo(4002);

        // 作废留审计（异步写入，轮询等落地）
        assertThat(awaitAuditCount("SETTLEMENT_HOLD_VOID", holdNo)).isTrue();
    }

    @Test
    @DisplayName("H5: 不存在的单号 release/void → 1002")
    void unknownHoldNoRejected() throws Exception {
        ResponseEntity<String> resp = post("/api/settlement/holds/release",
                "{\"holdNo\":\"NO-SUCH-HOLD\"}");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(resp.getBody()).path("code").asInt()).isEqualTo(1002);
    }

    // ------------------------------------------------------------------
    // ④ RBAC + query
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H6: 匿名 401；无角色会话 403（settlement:write 不是摆设）；query 可按 appId 过滤且不记审计")
    void rbacAndQuery() throws Exception {
        // 匿名：过滤器认证面
        HttpHeaders anon = new HttpHeaders();
        anon.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange("/api/settlement/holds", HttpMethod.POST,
                new HttpEntity<>("{}", anon), String.class).getStatusCode().value()).isEqualTo(401);

        // 无角色会话：认证过但无 settlement:write → 403
        com.newcoupon.infrastructure.persistence.po.AdminUserPO noRole =
                new com.newcoupon.infrastructure.persistence.po.AdminUserPO();
        noRole.setUsername("it-hold-norole-" + UUID.randomUUID().toString().substring(0, 8));
        noRole.setPasswordHash(passwordEncoder.encode(USER_PASSWORD));
        noRole.setDisplayName("hold无角色");
        noRole.setEnabled(1);
        noRole.setAuthVersion(0L);
        rbacMapper.insert(noRole);
        String noRoleSession = login(noRole.getUsername(), USER_PASSWORD);
        assertThat(postWithSession("/api/settlement/holds",
                holdBody("HOLD-H6-NOROLE", "1", "M", null, 100, "应被 403"),
                noRoleSession).getStatusCode().value()).isEqualTo(403);

        // query：建一条后按 appId 过滤能查到；query 不写审计
        String holdNo = "HOLD-H6-" + UUID.randomUUID().toString().substring(0, 8);
        post("/api/settlement/holds", holdBody(holdNo, "1", "M", null, 100, "查询-测试"));
        // 审计写入是异步的（AuditService 单线程 executor）：先等建动作的审计行落地，
        // 再取基线——否则"query 不记"断言会把建动作的迟到写入误判成 query 写的
        assertThat(awaitAuditCount("SETTLEMENT_HOLD_CREATE", holdNo))
                .as("建动作的审计行应在一秒内落地（异步 executor）").isTrue();
        Integer auditBefore = jdbc().queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE resource_id = ?", Integer.class, holdNo);
        ResponseEntity<String> query = post("/api/settlement/holds/query",
                "{\"appId\":\"1\",\"limit\":50}");
        assertThat(query.getStatusCode().value()).isEqualTo(200);
        JsonNode items = JSON.readTree(query.getBody()).path("data").path("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.toString()).contains(holdNo);
        Integer auditAfter = jdbc().queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE resource_id = ?", Integer.class, holdNo);
        assertThat(auditAfter).as("query 不记审计").isEqualTo(auditBefore);
    }

    /** 轮询等该动作的异步审计行出现（最多 3 秒；审计可丢语义下测试容错） */
    private boolean awaitAuditCount(String action, String holdNo) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Integer count = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_id = ?",
                    Integer.class, action, holdNo);
            if (count != null && count >= 1) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // ⑤ 审查 B 簇：同号异参重放不得静默返回旧行（4B 风控调参重发会误以为新金额生效）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H7: 同 holdNo 异参（金额/主体/模板/租户任一不同）→ 1000 拒绝；同参重放 200")
    void sameHoldNoDifferentParamsRejected() throws Exception {
        String holdNo = "HOLD-H7-" + UUID.randomUUID().toString().substring(0, 8);
        post("/api/settlement/holds", holdBody(holdNo, "1", "M", null, 500, "同号异参-基线"));

        // 同参重放：幂等成功（H1 已钉，这里作对照）
        assertThat(post("/api/settlement/holds",
                holdBody(holdNo, "1", "M", null, 500, "同号异参-基线")).getStatusCode().value())
                .isEqualTo(200);

        // 异参四连：金额/主体/模板/租户任一不同都拒绝，且不改原行
        for (String body : new String[]{
                holdBody(holdNo, "1", "M", null, 999, "同号异参-基线"),           // 金额不同
                holdBody(holdNo, "1", "OTHER", null, 500, "同号异参-基线"),       // 主体不同
                holdBody(holdNo, "1", "M", 1L, 500, "同号异参-基线"),             // 模板不同
                holdBody(holdNo, "2", "M", null, 500, "同号异参-基线")}) {        // 租户不同
            ResponseEntity<String> resp = post("/api/settlement/holds", body);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(JSON.readTree(resp.getBody()).path("code").asInt())
                    .as("同号异参拒绝。body=" + resp.getBody()).isEqualTo(1000);
        }
        assertThat(jdbc().queryForObject(
                "SELECT amount FROM coupon_settlement_hold WHERE hold_no = ?", Long.class, holdNo))
                .as("原行不被异参重放改动").isEqualTo(500L);
    }

    // ------------------------------------------------------------------
    // ⑥ 审查 H 簇：服务层自查（4B 直调入口绕过控制器 @Valid）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H8: 直调服务 place——负金额/超额/空 reason/超长 holdNo 全拒绝")
    void serviceLevelValidationForDirectCalls() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        com.newcoupon.application.service.SettlementHoldService service = holdService;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.place(request(
                "HOLD-H8A-" + suffix, "1", "M", null, -5000L, "负金额")))
                .isInstanceOf(com.newcoupon.common.BizException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.place(request(
                "HOLD-H8B-" + suffix, "1", "M", null, 0L, "零金额")))
                .isInstanceOf(com.newcoupon.common.BizException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.place(request(
                "HOLD-H8C-" + suffix, "1", "M", null, 10_000_000_000_000_005L, "超上限")))
                .isInstanceOf(com.newcoupon.common.BizException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.place(request(
                "HOLD-H8D-" + suffix, "1", "M", null, 100L, null)))
                .isInstanceOf(com.newcoupon.common.BizException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.place(request(
                "HOLD-H8E-" + suffix + "-0123456789012345678901234567890123456789012345678901234567890123",
                "1", "M", null, 100L, "超长单号")))
                .isInstanceOf(com.newcoupon.common.BizException.class);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_settlement_hold WHERE hold_no LIKE ?",
                Integer.class, "HOLD-H8%")).isZero();
    }

    private static com.newcoupon.application.dto.SettlementHoldDtos.HoldPlaceRequest request(
            String holdNo, String appId, String party, Long templateId, long amount, String reason) {
        return new com.newcoupon.application.dto.SettlementHoldDtos.HoldPlaceRequest(
                holdNo, appId, party, templateId, amount, reason, null);
    }

    // ------------------------------------------------------------------
    // ⑧ 审查 I② 簇：主体拼错的冻结永久命不中账单——place 响应带 matchedHistory 可见性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H10: matchedHistory——近 90 天该 (appId,party) 有分账历史=true；陌生主体=false")
    void placeResponseCarriesPartyHistoryMatch() throws Exception {
        // 自包含纪律：上一次运行/别的用例可能留下同款行（本用例曾用 template_id=1 污染过
        // 后续账单类——先清后插再清，插的历史行只在本用例生命周期内存在）
        jdbc().update("DELETE FROM coupon_share_detail WHERE usage_no LIKE 'U-H10-%'");
        // 造一段真实分账历史（直插明细——place 的 EXISTS 判据就是它）
        jdbc().update("INSERT INTO coupon_share_detail "
                        + "(id, user_coupon_id, user_id, template_id, app_id, settle_party_id, "
                        + " usage_no, order_no, order_line_no, platform_burden, merchant_burden, share_total) "
                        + "VALUES (?, 1, 1, 1, '1', 'M_MATCHED_H10', ?, 'O-H10', 'L1', 100, 0, 100)",
                System.nanoTime(), "U-H10-" + System.nanoTime());

        ResponseEntity<String> matched = post("/api/settlement/holds",
                holdBody("HOLD-H10A-" + UUID.randomUUID().toString().substring(0, 8),
                        "1", "M_MATCHED_H10", null, 100, "有历史主体"));
        assertThat(JSON.readTree(matched.getBody()).path("data").path("matchedHistory").asBoolean())
                .as("近 90 天有分账历史的主体=true（冻结大概率命中有意义的账单行）。body="
                        + matched.getBody())
                .isTrue();

        ResponseEntity<String> unmatched = post("/api/settlement/holds",
                holdBody("HOLD-H10B-" + UUID.randomUUID().toString().substring(0, 8),
                        "1", "ZZZ-NO-HISTORY-" + UUID.randomUUID().toString().substring(0, 8),
                        null, 100, "陌生主体"));
        assertThat(JSON.readTree(unmatched.getBody()).path("data").path("matchedHistory").asBoolean())
                .as("陌生主体=false——大概率拼错，人工核对（不强校验：新主体早期冻结合法）。body="
                        + unmatched.getBody())
                .isFalse();

        // 历史行是探针数据：留在库里会以 eligible=1 进入后续测试的账单（曾实际污染过
        // 同容器测试类的模板账单断言）——用完即清
        jdbc().update("DELETE FROM coupon_share_detail WHERE usage_no LIKE 'U-H10-%'");
    }

    // ------------------------------------------------------------------
    // ⑦ 审查 G 簇：并发同目标迁移，输家 0 行分支按当前行分类（确定性行锁交错）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H9: 并发同目标 release——输家按当前行分类幂等成功，不得误报'终态 ACTIVE'4002")
    void concurrentSameTargetTransitionLoserReadsFreshRow() throws Exception {
        String holdNo = "HOLD-H9-" + UUID.randomUUID().toString().substring(0, 8);
        holdService.place(request(holdNo, "1", "M", null, 100L, "并发交错"));

        // 赢家：事务内条件 UPDATE 改中 1 行但不提交（行锁在手=并发中"已执行未提交"）
        java.sql.Connection winner = dataSource.getConnection();
        winner.setAutoCommit(false);
        int hit = winner.createStatement().executeUpdate(
                "UPDATE coupon_settlement_hold SET status='RELEASED', released_by='WINNER', "
                        + "released_at=NOW() WHERE hold_no='" + holdNo + "' AND status='ACTIVE'");
        assertThat(hit).isEqualTo(1);

        // 输家：SELECT 读 ACTIVE，UPDATE 阻塞在赢家行锁上；赢家提交后才求值 → 0 行
        java.util.concurrent.atomic.AtomicReference<Object> loserResult = new java.util.concurrent.atomic.AtomicReference<>();
        Thread loser = new Thread(() -> {
            try {
                loserResult.set(holdService.release(holdNo));
            } catch (Throwable t) {
                loserResult.set(t);
            }
        }, "hold-transition-loser");
        loser.start();
        Thread.sleep(800);   // 让输家完成 SELECT 并阻塞在 UPDATE
        winner.commit();
        winner.close();
        loser.join(10_000);
        assertThat(loser.getState()).isEqualTo(Thread.State.TERMINATED);

        // 输家的 0 行分支必须重读当前行分类：RELEASED=幂等成功；旧实现按旧快照 ACTIVE 误报 4002
        assertThat(loserResult.get())
                .as("输家应幂等成功返回 RELEASED 视图，实际=" + loserResult.get())
                .isInstanceOf(com.newcoupon.application.dto.SettlementHoldDtos.HoldView.class);
        assertThat(((com.newcoupon.application.dto.SettlementHoldDtos.HoldView) loserResult.get())
                .status()).isEqualTo("RELEASED");
    }
}
