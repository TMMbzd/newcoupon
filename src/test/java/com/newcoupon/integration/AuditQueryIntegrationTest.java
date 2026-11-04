package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.AuditService;
import com.newcoupon.infrastructure.persistence.mapper.AuditLogMapper;
import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * V6 2C 审计查询刀·验收测试：查询/详情/导出（含一次性下载与 manifest）/verify 端点。
 *
 * <p>覆盖四块：
 * <ol>
 *   <li><b>查询</b>：过滤（action/actor）、游标分页续翻、时间窗校验（≤31 天/成对）、
 *       匿名拒绝（/api 管理面认证）+ 权限闸（audit:read）；</li>
 *   <li><b>详情</b>：快照全文（列表只给 200 字摘要，详情给全量）与哈希两列；</li>
 *   <li><b>导出</b>：异步任务 PENDING→DONE、BOM/manifest（行数+SHA-256+时间窗+过滤）、
 *       SHA-256 可复核、一次性下载（第二次 4002）、超行数上限落 FAILED；</li>
 *   <li><b>verify</b>（变异取证）：改一行 action → 返回第一个断点 {breakAtId,
 *       reason=RECORD_HASH_MISMATCH}；还原后 ok=true。混版链：把一条 v2 行手工改成
 *       v1 口径（hash_version=1 + v1 重算哈希）→ 仍 ok——证明校验按行版本选算法。</li>
 * </ol>
 *
 * <p>数据纪律（共享库基座）：动作列带唯一 marker（IT2C-*），断言永远先按 marker 过滤。
 * verify 是全链动作，篡改用例在 finally 里还原，不把断链留给后面的用例。
 *
 * <p>凭据：旧管理令牌（dev-admin-token）持全量权限码（LegacyAdminTokenAuthenticationFilter
 * 查表授权），直接过 audit:read/audit:export 的 @PreAuthorize——本类测端点行为，
 * RBAC 拒绝面的矩阵在 SecurityRbacIntegrationTest。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "coupon.audit.export.max-rows=3",   // 超限 FAILED 用例的触发线：本类 marker 行数控制在 2/4 两侧
        // 巡检任务装配进上下文（默认关）：用例直调 runOnce() 驱动——
        // 调度间隔保持默认 1h 初始延迟，测试窗口内后台轮次不会自己跑
        "coupon.audit.verify.enabled=true"
})
class AuditQueryIntegrationTest extends ShardedIntegrationTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_TOKEN = "dev-admin-token";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private com.newcoupon.application.service.AuditVerifyService verifyService;

    @Autowired
    private com.newcoupon.application.service.AuditChainVerifyJob verifyJob;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** 链头对账用例的删行/回插走裸 SQL（显式 id 保链位，MyBatis-Plus 自增插入做不到）。 */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private com.newcoupon.infrastructure.persistence.mapper.AuditExportJobMapper exportJobMapper;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 写一条带唯一 action marker 的审计行（异步落库），返回 marker。 */
    private String writeAuditRow(String marker, String afterSnapshot) {
        auditService.record(AuditService.AuditContext.of(
                "it-app", 4321L, "trace-" + marker,
                marker, "IT2C_RES", "res-" + marker,
                "{\"before\":1}", afterSnapshot,
                "127.0.0.1", 0,
                "TENANT", "it-app", null));
        return marker;
    }

    /** 轮询等 marker 行落库（审计是异步单线程写）。 */
    private List<AuditLogPO> waitFor(String marker, int minCount) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            List<AuditLogPO> rows = auditLogMapper.selectList(
                    new LambdaQueryWrapper<AuditLogPO>().eq(AuditLogPO::getAction, marker));
            if (rows.size() >= minCount) {
                return rows;
            }
            Thread.sleep(100);
        }
        return auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLogPO>().eq(AuditLogPO::getAction, marker));
    }

    /** POST /api/audit/**（带管理令牌），返回反序列化的 Result。只发一次——导出下载是一次性动作，重放会吃到自己的 4002。 */
    private JsonNode post(String path, String body) {
        String raw = postRaw(path, body).getBody();
        return raw == null ? null : parse(raw);
    }

    private ResponseEntity<String> postRaw(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private byte[] postBytes(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class).getBody();
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("bad json: " + body, e);
        }
    }

    /** 导出/查询共用的请求体：过滤=唯一 marker + 1 小时窗（一处拼接，防 .formatted 只作用于末段的优先级坑）。 */
    private static String bodyWithMarker(String marker) {
        return "{\"filter\":{\"action\":\"" + marker + "\",\"startTime\":\""
                + LocalDateTime.now().minusHours(1) + "\",\"endTime\":\""
                + LocalDateTime.now().plusMinutes(5) + "\"}}";
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("查询：action 过滤命中本用例行，actor 三列与截断摘要可读")
    void query_filtersAndReturnsActorColumns() throws Exception {
        String bigSnapshot = "{\"after\":\"" + "x".repeat(600) + "\"}";
        String marker = writeAuditRow("IT2C-Q1-" + UUID.randomUUID(), bigSnapshot);
        waitFor(marker, 1);   // 审计异步落库：先等行真的在库里再查

        JsonNode result = post("/api/audit/query", bodyWithMarker(marker));

        assertThat(result.path("code").asInt()).isEqualTo(0);
        JsonNode items = result.path("data").path("items");
        assertThat(items.size()).as("marker 恰好一行").isEqualTo(1);
        JsonNode row = items.get(0);
        assertThat(row.path("actorType").asText()).isEqualTo("TENANT");
        assertThat(row.path("actorId").asText()).isEqualTo("it-app");
        assertThat(row.path("userId").asLong()).isEqualTo(4321L);
        // 列表快照是 200 字符摘要：不把 600 字符全文塞进列表
        assertThat(row.path("afterSummary").asText()).hasSizeLessThanOrEqualTo(200);

        // 查询自身也留痕（action=AUDIT_QUERY，旧令牌无个人身份 → actor=SYSTEM）
        List<AuditLogPO> selfAudit = waitFor("AUDIT_QUERY", 1);
        assertThat(selfAudit).isNotEmpty();
        assertThat(selfAudit.get(selfAudit.size() - 1).getActorType()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("查询：游标分页——limit 页满给 nextCursor，续翻取到余下行后收 null")
    void query_cursorPagination() throws Exception {
        String marker = "IT2C-PAGE-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            writeAuditRow(marker, "{\"i\":" + i + "}");
        }
        waitFor(marker, 3);

        String window = "\"startTime\":\"%s\",\"endTime\":\"%s\""
                .formatted(LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(5));
        JsonNode page1 = post("/api/audit/query",
                "{\"limit\":2,\"filter\":{\"action\":\"%s\",%s}}".formatted(marker, window));
        assertThat(page1.path("data").path("items").size()).isEqualTo(2);
        long cursor = page1.path("data").path("nextCursor").asLong();
        assertThat(cursor).as("页满必有下一页游标").isGreaterThan(0);

        JsonNode page2 = post("/api/audit/query",
                "{\"limit\":2,\"cursor\":%d,\"filter\":{\"action\":\"%s\",%s}}"
                        .formatted(cursor, marker, window));
        assertThat(page2.path("data").path("items").size()).isEqualTo(1);
        assertThat(page2.path("data").path("nextCursor").isNull()).as("取完即末页").isTrue();
    }

    @Test
    @DisplayName("查询：时间窗超 31 天 / 只给一端 / limit 超 200 → 1000 参数错")
    void query_rejectsBadWindowAndLimit() {
        LocalDateTime now = LocalDateTime.now();
        assertThat(post("/api/audit/query",
                "{\"filter\":{\"startTime\":\"%s\",\"endTime\":\"%s\"}}"
                        .formatted(now.minusDays(40), now)).path("code").asInt())
                .as("40 天窗口").isEqualTo(1000);
        assertThat(post("/api/audit/query",
                "{\"filter\":{\"startTime\":\"%s\"}}".formatted(now)).path("code").asInt())
                .as("只给 startTime").isEqualTo(1000);
        assertThat(post("/api/audit/query", "{\"limit\":201}").path("code").asInt())
                .as("limit>200").isEqualTo(1000);
    }

    @Test
    @DisplayName("安全面：匿名调 /api/audit/query → 401（管理面认证在前，权限码都轮不到判）")
    void query_rejectsAnonymous() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange("/api/audit/query", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    @Test
    @DisplayName("详情：快照全文（非摘要）+ 哈希链两列与 hash_version")
    void detail_returnsFullSnapshot() throws Exception {
        String marker = writeAuditRow("IT2C-D1-" + UUID.randomUUID(),
                "{\"after\":\"" + "y".repeat(600) + "\"}");
        List<AuditLogPO> rows = waitFor(marker, 1);
        assertThat(rows).hasSize(1);

        JsonNode detail = post("/api/audit/detail", "{\"id\":" + rows.get(0).getId() + "}");
        assertThat(detail.path("code").asInt()).isEqualTo(0);
        JsonNode data = detail.path("data");
        assertThat(data.path("afterSnapshot").asText())
                .as("详情给快照全文（列表才是 200 字摘要）")
                .hasSizeGreaterThanOrEqualTo(600);
        assertThat(data.path("recordHash").asText()).hasSize(64);
        assertThat(data.path("prevHash").asText()).hasSize(64);
        assertThat(data.path("hashVersion").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    @Test
    @DisplayName("导出：PENDING→DONE 轮询，BOM+manifest（行数/SHA-256/时间窗/过滤），SHA-256 可复核，下载一次性")
    void export_fullFlow() throws Exception {
        String marker = "IT2C-E1-" + UUID.randomUUID();
        writeAuditRow(marker, "{\"e\":1}");
        writeAuditRow(marker, "{\"e\":2}");
        waitFor(marker, 2);

        JsonNode created = post("/api/audit/export", bodyWithMarker(marker));
        assertThat(created.path("code").asInt()).isEqualTo(0);
        String jobNo = created.path("data").path("jobNo").asText();
        assertThat(jobNo).startsWith("AUDIT-EXP-");

        // 轮询到 DONE（构建在专用单线程，正常毫秒级；留 15 秒余量扛 CI 抖动）
        AtomicReference<JsonNode> status = new AtomicReference<>();
        for (int i = 0; i < 150; i++) {
            JsonNode s = post("/api/audit/export/status", "{\"jobNo\":\"" + jobNo + "\"}");
            status.set(s);
            if ("DONE".equals(s.path("data").path("status").asText())) {
                break;
            }
            Thread.sleep(100);
        }
        JsonNode done = status.get().path("data");
        assertThat(done.path("status").asText()).as("导出构建应完成：jobNo=" + jobNo).isEqualTo("DONE");
        assertThat(done.path("rowCount").asLong()).isEqualTo(2);

        // 下载：BOM 开头 + manifest 首行 + 两行数据
        byte[] csv = postBytes("/api/audit/export/download", "{\"jobNo\":\"" + jobNo + "\"}");
        assertThat(csv.length).isGreaterThan(0);
        assertThat(csv[0] & 0xFF).as("UTF-8 BOM 第一字节").isEqualTo(0xEF);
        String text = new String(csv, StandardCharsets.UTF_8);
        // BOM 解码为 U+FEFF 字符——行断言前剥掉（BOM 是给 Excel 的，不是给文本比较的）
        assertThat(text.charAt(0)).isEqualTo('\uFEFF');
        text = text.substring(1);
        String[] lines = text.split("\r\n");
        assertThat(lines.length).as("manifest + 表头 + 2 数据行").isEqualTo(4);
        assertThat(lines[0]).startsWith("MANIFEST,").contains(marker);
        assertThat(lines[1]).startsWith("id,created_at,app_id,actor_type");
        assertThat(lines[2]).contains(marker);
        assertThat(lines[3]).contains(marker);

        // manifest 的 SHA-256 复核：盖"表头+数据行"（manifest 行自身除外）
        String dataPortion = text.substring(text.indexOf("\r\n") + 2);
        assertThat(lines[0]).contains(sha256(dataPortion));

        // 一次性下载：第二次 → 4002
        JsonNode second = post("/api/audit/export/download", "{\"jobNo\":\"" + jobNo + "\"}");
        assertThat(second.path("code").asInt()).isEqualTo(4002);

        // 导出动作自身留痕（受理+下载两条 AUDIT_EXPORT）
        List<AuditLogPO> selfAudit = waitFor("AUDIT_EXPORT", 2);
        assertThat(selfAudit.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("导出：结果行数超上限（max-rows=3）→ 任务 FAILED 且错误信息提示缩窗")
    void export_failsOverRowLimit() throws Exception {
        String marker = "IT2C-E2-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            writeAuditRow(marker, "{\"i\":" + i + "}");
        }
        waitFor(marker, 4);

        JsonNode created = post("/api/audit/export", bodyWithMarker(marker));
        String jobNo = created.path("data").path("jobNo").asText();

        AtomicReference<JsonNode> status = new AtomicReference<>();
        for (int i = 0; i < 150; i++) {
            JsonNode s = post("/api/audit/export/status", "{\"jobNo\":\"" + jobNo + "\"}");
            status.set(s);
            String st = s.path("data").path("status").asText();
            if ("DONE".equals(st) || "FAILED".equals(st)) {
                break;
            }
            Thread.sleep(100);
        }
        JsonNode done = status.get().path("data");
        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(done.path("error").asText()).contains("上限");

        // FAILED 任务不可下载 → 4002
        JsonNode download = post("/api/audit/export/download", "{\"jobNo\":\"" + jobNo + "\"}");
        assertThat(download.path("code").asInt()).isEqualTo(4002);
    }

    // ------------------------------------------------------------------
    // verify（变异取证）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("verify（先变异后还原）：改一行 action → 第一个断点 {breakAtId, RECORD_HASH_MISMATCH}；还原后全链 ok")
    void verify_detectsTampering() throws Exception {
        String marker = writeAuditRow("IT2C-V1-" + UUID.randomUUID(), "{\"v\":1}");
        List<AuditLogPO> rows = waitFor(marker, 1);
        AuditLogPO victim = rows.get(0);
        try {
            // 变异：模拟攻击者直改库（改内容字段，哈希未随之重算）
            auditLogMapper.update(null, new LambdaUpdateWrapper<AuditLogPO>()
                    .eq(AuditLogPO::getId, victim.getId())
                    .set(AuditLogPO::getAction, "TAMPERED"));

            JsonNode broken = post("/api/audit/verify", "{}");
            assertThat(broken.path("data").path("ok").asBoolean()).isFalse();
            assertThat(broken.path("data").path("breakAtId").asLong()).isEqualTo(victim.getId());
            assertThat(broken.path("data").path("reason").asText()).isEqualTo("RECORD_HASH_MISMATCH");
        } finally {
            // 还原：把链修回合法状态（内容复原 = 重算哈希重新与存储值一致），别把断链留给后续用例
            auditLogMapper.update(null, new LambdaUpdateWrapper<AuditLogPO>()
                    .eq(AuditLogPO::getId, victim.getId())
                    .set(AuditLogPO::getAction, marker));
        }

        JsonNode ok = post("/api/audit/verify", "{}");
        assertThat(ok.path("data").path("ok").asBoolean())
                .as("还原后全链应恢复一致").isTrue();
        assertThat(ok.path("data").path("checked").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("verify 对 v1 行豁免内容重算（C1）：v1 行内容被改不报断（纳秒口径永久盲区，衔接仍校验）；还原后仍 ok")
    void verify_v1RowsSkipContentRecompute_documentedBlindSpot() throws Exception {
        String marker = writeAuditRow("IT2C-V2-" + UUID.randomUUID(), "{\"v\":2}");
        List<AuditLogPO> rows = waitFor(marker, 1);
        AuditLogPO row = rows.get(0);
        try {
            // 构造"存量 v1 行"并篡改其内容：hash_version 落 1 + 改 action，record_hash 原样不动。
            // v1 时代 createdAt 带纳秒进哈希、列是秒级（L81）——v1 行的哈希从写入起就
            // 不可能从库内数据重算，verify 对 v1 行只做衔接校验（C1 裁定）。
            // 代价如实钉住：改 v1 行内容（不动哈希）衔接也不破——**检测不到**，这是盲区不是能力
            auditLogMapper.update(null, new LambdaUpdateWrapper<AuditLogPO>()
                    .eq(AuditLogPO::getId, row.getId())
                    .set(AuditLogPO::getHashVersion, 1)
                    .set(AuditLogPO::getAction, "TAMPERED-V1-BLINDSPOT"));

            com.newcoupon.application.dto.AuditDtos.AuditVerifyResult r = verifyService.verifyChain();
            assertThat(r.ok())
                    .as("v1 行内容篡改是已登记盲区（L81）：衔接未破则 verify 不报——"
                            + "若这里报了 RECORD_HASH_MISMATCH，说明豁免逻辑回退").isTrue();

            // 对照组：同一行改回 v2 再改内容 → 必须报（v2 行内容校验不受豁免影响）
            auditLogMapper.update(null, new LambdaUpdateWrapper<AuditLogPO>()
                    .eq(AuditLogPO::getId, row.getId())
                    .set(AuditLogPO::getHashVersion, 2));
            com.newcoupon.application.dto.AuditDtos.AuditVerifyResult broken =
                    verifyService.verifyChain();
            assertThat(broken.ok()).isFalse();
            assertThat(broken.reason()).isEqualTo("RECORD_HASH_MISMATCH");
            assertThat(broken.breakAtId()).isEqualTo(row.getId());
        } finally {
            auditLogMapper.update(null, new LambdaUpdateWrapper<AuditLogPO>()
                    .eq(AuditLogPO::getId, row.getId())
                    .set(AuditLogPO::getHashVersion, 2)
                    .set(AuditLogPO::getAction, marker));
        }

        assertThat(verifyService.verifyChain().ok()).as("还原后全链恢复一致").isTrue();
    }

    @Test
    @DisplayName("verify 链头对账（M1）：删末行 head 残留旧哈希 → CHAIN_HEAD_MISMATCH；还原后 ok")
    void verify_crossChecksChainHead() throws Exception {
        // 哨兵行先落库并等到它：异步审计单线程 FIFO，哨兵可见=队列已空，
        // 之后不再发 HTTP（verify 直调服务），末行在删/验之间不会被并发追加
        String marker = writeAuditRow("IT2C-HEAD-" + UUID.randomUUID(), "{\"h\":1}");
        waitFor(marker, 1);
        com.newcoupon.infrastructure.persistence.po.AuditLogPO last = lastRow();
        assertThat(last).isNotNull();
        assertThat(last.getAction()).as("哨兵行应是当前末行").isEqualTo(marker);
        String deletedHash = last.getRecordHash();
        try {
            // 攻击模拟（可检出形态）：删末行但 head 残留被删行的哈希——只走链不核头对此失明
            // （链体 1..N-1 衔接完好，唯独 head 指向不存在的行）。
            // 注：攻击者若同时把 head 修成新末行哈希，库内证据无法检出——head 与表同库
            // 同权限，抗"DB 主体亲自作案"要靠库外锚点（WORM/异地日志投递），runbook §5 已写明
            jdbc.update("DELETE FROM audit_log WHERE id = ?", last.getId());

            com.newcoupon.application.dto.AuditDtos.AuditVerifyResult r = verifyService.verifyChain();
            assertThat(r.ok()).as("尾删+head 残留必须被链头对账抓到").isFalse();
            assertThat(r.reason()).isEqualTo("CHAIN_HEAD_MISMATCH");
        } finally {
            // 原样塞回（显式 id 保持链位），head 恢复为该行哈希
            jdbc.update("INSERT INTO audit_log (id, app_id, user_id, trace_id, action, resource_type, "
                            + "resource_id, before_snapshot, after_snapshot, ip, result_code, "
                            + "actor_type, actor_id, session_id, hash_version, prev_hash, record_hash, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    last.getId(), last.getAppId(), last.getUserId(), last.getTraceId(),
                    last.getAction(), last.getResourceType(), last.getResourceId(),
                    last.getBeforeSnapshot(), last.getAfterSnapshot(), last.getIp(),
                    last.getResultCode(), last.getActorType(), last.getActorId(), last.getSessionId(),
                    last.getHashVersion(), last.getPrevHash(), deletedHash,
                    java.sql.Timestamp.valueOf(last.getCreatedAt()));
            jdbc.update("UPDATE audit_chain_head SET last_hash = ? WHERE id = 1", deletedHash);
        }

        assertThat(verifyService.verifyChain().ok())
                .as("还原（行+head）后全链与链头恢复一致").isTrue();
    }

    /** 当前全表末条（id 最大）。 */
    private com.newcoupon.infrastructure.persistence.po.AuditLogPO lastRow() {
        List<AuditLogPO> rows = auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLogPO>()
                        .orderByDesc(AuditLogPO::getId)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------
    // 审查 minors：导出窗口冻结（m1）/ 类型不合法 400（m4）/ 导出 @Valid（m5）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("导出时间窗受理即冻结（m1）：任务行的 filters_json 存绝对时间，不是留空到构建时再解析")
    void export_windowFrozenAtAcceptance() {
        // 不带时间过滤受理（默认窗语义）：任务行里必须已解析成绝对 startTime/endTime——
        // 否则构建排队跨过整点/零点后窗口漂移，导出件与受理语义对不上
        JsonNode created = post("/api/audit/export", "{}");
        assertThat(created.path("code").asInt()).isEqualTo(0);
        String jobNo = created.path("data").path("jobNo").asText();

        com.newcoupon.infrastructure.persistence.po.AuditExportJobPO job =
                exportJobMapper.selectOne(new LambdaQueryWrapper<
                        com.newcoupon.infrastructure.persistence.po.AuditExportJobPO>()
                        .eq(com.newcoupon.infrastructure.persistence.po.AuditExportJobPO::getJobNo, jobNo));
        assertThat(job).isNotNull();
        assertThat(job.getFiltersJson())
                .as("受理时解析的绝对时间窗（startTime/endTime 非 null）")
                .contains("\"startTime\":\"2")
                .contains("\"endTime\":\"2");
    }

    @Test
    @DisplayName("参数类型不合法 → 400+1000（m4：此前 200+5000+ERROR 假告警）")
    void malformedTimeParam_rejectedAs400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        ResponseEntity<String> resp = rest.exchange("/api/audit/query", HttpMethod.POST,
                new HttpEntity<>("{\"filter\":{\"startTime\":\"2026-11-04 10:00:00\"}}", headers),
                String.class);
        assertThat(resp.getStatusCode().value()).as("空格分隔的时间串不是合法 ISO LocalDateTime").isEqualTo(400);
        assertThat(resp.getBody()).contains("\"code\":1000");
    }

    @Test
    @DisplayName("导出请求同样吃 @Valid（m5）：超长 actorId → 400+1000，而不是打穿到 SQL 层")
    void exportValidatesFilterSizes() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        ResponseEntity<String> resp = rest.exchange("/api/audit/export", HttpMethod.POST,
                new HttpEntity<>("{\"filter\":{\"actorId\":\"" + "a".repeat(70_000) + "\"}}", headers),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("\"code\":1000");
    }

    // ------------------------------------------------------------------
    // 管理面写动作留痕（M3）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("管理面写动作留痕（M3）：注册接入方 → APP_CREATE 进 audit_log（rotate 此前连专表都没有）")
    void adminWriteAction_audited() throws Exception {
        String appId = "it-m3-app-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = post("/api/apps", "{\"appId\":\"" + appId + "\",\"appName\":\"M3 审计验签应用\"}");
        assertThat(created.path("code").asInt()).as("注册接入方应成功: " + created).isEqualTo(0);

        List<AuditLogPO> rows = waitFor("APP_CREATE", 1);
        AuditLogPO row = rows.stream()
                .filter(r -> appId.equals(r.getResourceId()))
                .findFirst().orElse(null);
        assertThat(row).as("APP_CREATE 审计行（resourceId=appId）必须出现").isNotNull();
        assertThat(row.getResourceType()).isEqualTo("OPEN_APP");
        // 旧管理令牌（本用例的凭据）是共享机器凭据 → actor=SYSTEM；动作照记（M3 闸门裁定），
        // 个人身份由控制台会话面（actor=ADMIN+userId）补齐
        assertThat(row.getActorType()).isEqualTo("SYSTEM");
        assertThat(row.getAfterSnapshot()).contains("M3 审计验签应用");
    }

    // ------------------------------------------------------------------
    // 每日巡检任务（AuditChainVerifyJob）
    // ------------------------------------------------------------------    @Test
    @DisplayName("巡检任务：健康轮 true；断链轮 false + 断链/轮次/活性指标（M2）；还原后恢复 true")
    void chainVerifyJob_reportsBrokenWithMetric() throws Exception {
        String marker = writeAuditRow("IT2C-JOB-" + UUID.randomUUID(), "{\"j\":1}");
        List<AuditLogPO> rows = waitFor(marker, 1);
        AuditLogPO victim = rows.get(0);
        double gaugeBefore = meterRegistry.get("coupon_audit_chain_last_success_epoch_ms")
                .gauge().value();
        try {
            assertThat(verifyJob.runOnce()).as("健康链：巡检通过").isTrue();
            assertThat(meterRegistry.get("coupon_audit_chain_verify_total")
                    .tag("result", "ok").counter().count())
                    .as("健康轮也要计数（M2：只有失败才计数=『从未跑过』与『跑且健康』不可区分）")
                    .isGreaterThanOrEqualTo(1.0);
            assertThat(meterRegistry.get("coupon_audit_chain_last_success_epoch_ms").gauge().value())
                    .as("成功轮更新最近成功时刻 gauge")
                    .isGreaterThanOrEqualTo(gaugeBefore);

            auditLogMapper.update(null, new LambdaUpdateWrapper<AuditLogPO>()
                    .eq(AuditLogPO::getId, victim.getId())
                    .set(AuditLogPO::getAction, "TAMPERED-BY-JOB-TEST"));
            assertThat(verifyJob.runOnce()).as("断链：巡检必须失败").isFalse();
            assertThat(meterRegistry.get("coupon_audit_chain_broken_total")
                    .tag("reason", "RECORD_HASH_MISMATCH").counter().count())
                    .as("断链指标已按原因计数").isGreaterThanOrEqualTo(1.0);
            assertThat(meterRegistry.get("coupon_audit_chain_verify_total")
                    .tag("result", "broken").counter().count())
                    .as("断链轮计数与 ok 分标签").isGreaterThanOrEqualTo(1.0);
        } finally {
            auditLogMapper.update(null, new LambdaUpdateWrapper<AuditLogPO>()
                    .eq(AuditLogPO::getId, victim.getId())
                    .set(AuditLogPO::getAction, marker));
        }
        assertThat(verifyJob.runOnce()).as("还原后：巡检恢复通过").isTrue();
    }

    private static String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
