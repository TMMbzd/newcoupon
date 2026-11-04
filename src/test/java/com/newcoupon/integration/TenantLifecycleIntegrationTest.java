package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.RefundRequest;
import com.newcoupon.application.service.ActivityAppService;
import com.newcoupon.application.service.DailyBillService;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TenantLifecycleService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.infrastructure.persistence.mapper.AdminRbacMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponShareDetailMapper;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppLifecycleEventMapper;
import com.newcoupon.infrastructure.persistence.po.AdminUserPO;
import com.newcoupon.infrastructure.persistence.po.OpenAppLifecycleEventPO;
import com.newcoupon.open.api.dto.CalculateRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * V6 2B 第一刀验收（设计：docs/research-2026-10-21/01 §4 2B，裁剪版）。
 *
 * <p>覆盖矩阵：
 * <ol>
 *   <li>状态机：合法链 DRAFT→PENDING_REVIEW→SANDBOX_ACTIVE→ACTIVE（HTTP 全链）+
 *       每步事件留痕（from/to/action/operator）；</li>
 *   <li>非法迁移 4002（含"当前状态允许什么"的报错）与未知动作 1000；</li>
 *   <li>suspend → 开放面 6013（HTTP 403）；resume → 恢复放行；</li>
 *   <li>close 链：CLOSING 冻结（6013）→ CLOSED 撤密钥（closed_at/retention_until 落库、
 *       旧密钥即时作废）；</li>
 *   <li>delete 双人复核：缺第二人拒 / legal_hold 拒 / 密码错拒 / 成功后 DELETED；</li>
 *   <li>数据导出：一次性（二次 4002）+ X-Data-Sha256 可复现 + CLOSED 后拒；</li>
 *   <li>沙箱：sbx- 前缀建 app（SANDBOX_ACTIVE）→ 建券/领/锁/核销照常 →
 *       退款 4002、预算懒建 4002（券能发、钱不动）；</li>
 *   <li>日账单跳过沙箱模板（沙箱核销分账行存在，但账单无该模板行）。</li>
 * </ol>
 *
 * <p>数据纪律（共享库基座）：appId/登录名带唯一尾缀，不清理。
 * 内部面 HTTP 一律带 X-Admin-Token（2A-2 翻面后 /api 全方法要认证；
 * 旧令牌持有者=平台管理员全量权限，含 tenant:lifecycle）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
        "coupon.admin.token=test-admin-token"
})
class TenantLifecycleIntegrationTest extends ShardedIntegrationTestSupport {

    private static final String ADMIN_TOKEN = "test-admin-token";
    /** 模拟具名管理员会话：currentOperator() 从 SecurityContext 取 AdminPrincipal。 */
    static final class NamedOperator implements AutoCloseable {
        NamedOperator(String username) {
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            new com.newcoupon.infrastructure.security.AdminPrincipal(1L, username),
                            null, java.util.List.of()));
        }

        @Override
        public void close() {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private static final String APPROVER_PASSWORD = "it-approver-pass-123";

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private OpenAppService openAppService;
    @Autowired private TenantLifecycleService lifecycleService;
    @Autowired private TemplateAppService templateAppService;
    @Autowired private ActivityAppService activityAppService;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private TradeAppService tradeAppService;
    @Autowired private DailyBillService dailyBillService;
    @Autowired private OpenAppRepository openAppRepository;
    @Autowired private OpenAppLifecycleEventMapper eventMapper;
    @Autowired private CouponShareDetailMapper shareDetailMapper;
    @Autowired private CouponDailyBillMapper billMapper;
    @Autowired private AdminRbacMapper rbacMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate rest;
    @Autowired private org.springframework.context.ApplicationContext context;

    // ------------------------------------------------------------------
    // HTTP 小工具（内部面）
    // ------------------------------------------------------------------

    /** 内部面 POST：带 X-Admin-Token 的 JSON 调用（lifecycle 等） */
    private ResponseEntity<String> adminPost(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    /** 内部面 GET（data-export）：拿原始字节，响应头里校验 SHA-256 */
    private ResponseEntity<byte[]> adminGetBytes(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private String lifecycle(String appId, String action) {
        return adminPost("/api/apps/" + appId + "/lifecycle",
                "{\"action\":\"" + action + "\",\"reason\":\"it-" + action + "\"}").getBody();
    }

    /** 开放面签名 GET 的探针：receive-async 查询——能过签名链即证明生命周期放行
     *  （业务层 3005"任务不存在"也算过）；被拦则是 403+6013 */
    private OpenApiTestClient.Response openProbe(int port, String appId, String secret) throws Exception {
        return new OpenApiTestClient(port).get(
                "/open/v1/coupons/receive-async/it-probe", "userId=42", appId, secret);
    }

    private int port() {
        return context.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 建一个 PROD 应用（入口态 ACTIVE）并返回 appId 与明文 SK */
    private record CreatedApp(String appId, String secret) {
    }

    private CreatedApp createProdApp() {
        String appId = unique("lc-it");
        OpenAppService.AppWithSecret created = openAppService.create(new CreateAppRequest(
                appId, "生命周期验证券商", 0, 0, "it@example.com"));
        return new CreatedApp(appId, created.secret());
    }

    /** 建一个 enabled 管理员（delete 双人复核的第二人） */
    private String createApprover() {
        AdminUserPO user = new AdminUserPO();
        user.setUsername(unique("it-approver"));
        user.setPasswordHash(passwordEncoder.encode(APPROVER_PASSWORD));
        user.setDisplayName("it-approver");
        user.setEnabled(1);
        user.setAuthVersion(0L);
        rbacMapper.insert(user);
        return user.getUsername();
    }

    // ------------------------------------------------------------------
    // ① 状态机：合法链 + 留痕
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合法链：DRAFT→submit_review→approve_sandbox→activate→ACTIVE（HTTP 全链），事件逐步留痕")
    void legalChain_walksDraftToActive() {
        CreatedApp app = createProdApp();
        // 入口态裁定：内部面建的 PROD 应用直接 ACTIVE；测状态机前用 SQL 置回 DRAFT
        // （DRAFT/PENDING_REVIEW 是给后续自助注册留的入口态，本刀无自助端点）
        jdbc.update("UPDATE open_app SET lifecycle_status='DRAFT' WHERE app_id=?", app.appId());

        assertThat(lifecycle(app.appId(), "submit_review")).contains("\"code\":0").contains("PENDING_REVIEW");
        assertThat(lifecycle(app.appId(), "approve_sandbox")).contains("SANDBOX_ACTIVE");
        assertThat(lifecycle(app.appId(), "activate")).contains("ACTIVE");

        assertThat(openAppRepository.findApp(app.appId()).orElseThrow().lifecycle().name())
                .isEqualTo("ACTIVE");

        // 留痕：三行事件按时间序，from/to 与状态机一致，operator 取自旧令牌身份（admin）
        List<OpenAppLifecycleEventPO> events = eventMapper.selectByApp(app.appId());
        assertThat(events).extracting(OpenAppLifecycleEventPO::getAction)
                .containsExactly("submit_review", "approve_sandbox", "activate");
        assertThat(events).extracting(OpenAppLifecycleEventPO::getOperator).containsOnly("admin");
        assertThat(events.get(2).getFromStatus()).isEqualTo("SANDBOX_ACTIVE");
        assertThat(events.get(2).getToStatus()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------
    // ② 状态机：非法迁移 / 未知动作
    // ------------------------------------------------------------------

    @Test
    @DisplayName("非法迁移 4002（报错带当前状态与允许动作）；未知动作 1000")
    void illegalTransition_rejected() {
        CreatedApp app = createProdApp();   // 入口态 ACTIVE

        // ACTIVE 不支持 activate（自迁移不在白名单）；报错要告诉运营"现在能做什么"
        assertThatThrownBy(() -> lifecycleService.transition(app.appId(),
                new TenantLifecycleService.LifecycleRequest("activate", "it", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法生命周期迁移")
                .hasMessageContaining("suspend")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        // DRAFT 直接 delete（跳整条链）也是非法——白名单制的意义就在这
        jdbc.update("UPDATE open_app SET lifecycle_status='DRAFT' WHERE app_id=?", app.appId());
        assertThatThrownBy(() -> lifecycleService.transition(app.appId(),
                new TenantLifecycleService.LifecycleRequest("delete", "it", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        // 拼错的动作名是参数错误，不是状态冲突（两种失败要能分开排障）
        assertThatThrownBy(() -> lifecycleService.transition(app.appId(),
                new TenantLifecycleService.LifecycleRequest("delet", "it", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    // ------------------------------------------------------------------
    // ③ suspend/resume：开放面 6013 拦截与恢复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("suspend 后开放面 403+6013（平台停用语义，与签名错 6003 区分）；resume 恢复放行")
    void suspendBlocksOpenFace_resumeRestores() throws Exception {
        CreatedApp app = createProdApp();
        int port = port();

        // 基线：ACTIVE 时同一探针能过签名链（3005=业务层的"任务不存在"，认证已通过）
        OpenApiTestClient.Response before = openProbe(port, app.appId(), app.secret());
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.body()).contains("3005");

        assertThat(lifecycle(app.appId(), "suspend")).contains("SUSPENDED");

        OpenApiTestClient.Response suspended = openProbe(port, app.appId(), app.secret());
        assertThat(suspended.status()).as("平台停用语义 → HTTP 403").isEqualTo(403);
        assertThat(suspended.body()).contains("6013").doesNotContain("6003");

        assertThat(lifecycle(app.appId(), "resume")).contains("ACTIVE");
        OpenApiTestClient.Response resumed = openProbe(port, app.appId(), app.secret());
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.body()).contains("3005");
    }

    // ------------------------------------------------------------------
    // ④ close 链：CLOSING 冻结 + CLOSED 撤密钥
    // ------------------------------------------------------------------

    @Test
    @DisplayName("close→CLOSING 开放面 6013；confirm_close→CLOSED 落 closed_at/retention_until 且旧密钥即时作废")
    void closeFreezesThenRevokesSecrets() throws Exception {
        CreatedApp app = createProdApp();
        int port = port();

        assertThat(lifecycle(app.appId(), "close")).contains("CLOSING");
        // CLOSING 即冻结写入：签名链在验签之前就被生命周期拦下（403+6013）
        OpenApiTestClient.Response closing = openProbe(port, app.appId(), app.secret());
        assertThat(closing.status()).isEqualTo(403);
        assertThat(closing.body()).contains("6013");

        assertThat(lifecycle(app.appId(), "confirm_close")).contains("CLOSED");

        com.newcoupon.domain.open.OpenApp closed = openAppRepository.findApp(app.appId()).orElseThrow();
        assertThat(closed.closedAt()).as("CLOSED 迁移写入注销时刻").isNotNull();
        assertThat(closed.retentionUntil())
                .as("默认保留期 = 注销日 + 180 天（只写不做，清理是 runbook 手动事项）")
                .isEqualTo(closed.closedAt().toLocalDate().plusDays(180));

        // 撤密钥（审查修正后语义）：CURRENT 零窗口降级 + 全部 ROTATING 立即 INVALID——
        // 注销前处于 7 天轮换期的旧密钥也一并死亡，"整串钥匙作废"
        List<OpenAppRepository.SecretRecord> secrets = openAppRepository.findAllSecrets(app.appId());
        assertThat(secrets).as("confirm_close 内做过一次零窗口轮换").hasSize(2);
        // rotate 插入的新 CURRENT 键明文丢弃（无人持有），旧键 CURRENT→ROTATING 即刻过期、
        // 轮换期残留 ROTATING→INVALID——不变量=除新 CURRENT 外整串旧密钥全部死亡
        OpenAppRepository.SecretRecord fresh = secrets.stream()
                .filter(s -> s.status() == OpenAppRepository.SecretRecord.STATUS_CURRENT)
                .findFirst().orElseThrow();
        assertThat(fresh.expireTime()).as("新 CURRENT 是 rotate 插入的零窗口键").isNull();
        assertThat(secrets.stream()
                .filter(s -> s != fresh)
                .allMatch(s -> s.status() == OpenAppRepository.SecretRecord.STATUS_INVALID
                        && (s.expireTime() == null || !s.expireTime()
                                .isAfter(LocalDateTime.now().plusMinutes(1)))))
                .as("所有旧密钥（含轮换期残留）全部 INVALID 且失效时刻不是未来——整串都死了").isTrue();

        // 旧密钥再调开放面：仍是 6013（生命周期判定在验签之前，被停用的租户不必先修签名）
        OpenApiTestClient.Response closedProbe = openProbe(port, app.appId(), app.secret());
        assertThat(closedProbe.status()).isEqualTo(403);
        assertThat(closedProbe.body()).contains("6013");
    }

    // ------------------------------------------------------------------
    // ⑤ delete：双人复核 + legal_hold
    // ------------------------------------------------------------------

    @Test
    @DisplayName("delete 双人复核：缺第二人拒 / legal_hold 拒 / 密码错拒 / 成功后 DELETED")
    void deleteRequiresDualPersonAndLegalHoldClear() {
        CreatedApp app = createProdApp();
        String approver = createApprover();
        lifecycleService.transition(app.appId(),
                new TenantLifecycleService.LifecycleRequest("close", "it", null, null));
        lifecycleService.transition(app.appId(),
                new TenantLifecycleService.LifecycleRequest("confirm_close", "it", null, null));

        // 旧管理令牌发起 delete → 4002（审查修正②：不可逆删除必须具名会话，防单人双帽）
        String legacyRejected = adminPost("/api/apps/" + app.appId() + "/lifecycle",
                "{\"action\":\"delete\",\"reason\":\"it\",\"secondApproverUsername\":\""
                + approver + "\",\"secondApproverPassword\":\"" + APPROVER_PASSWORD + "\"}").getBody();
        assertThat(legacyRejected).contains("4002").contains("具名管理员会话");

        // 具名会话发起、缺第二人 → 4002"双人"（模拟会话身份直调服务层）
        try (var ctx = new NamedOperator("it-admin")) {
            assertThatThrownBy(() -> lifecycleService.transition(app.appId(),
                    new TenantLifecycleService.LifecycleRequest("delete", "it", null, null)))
                    .isInstanceOf(com.newcoupon.common.BizException.class)
                    .hasMessageContaining("双人");
        }


        // legal_hold=1：即使双人到场也拒（法务保留优先于运营意愿）——具名会话直调
        jdbc.update("UPDATE open_app SET legal_hold=1 WHERE app_id=?", app.appId());
        try (var ctx = new NamedOperator("it-admin")) {
            assertThatThrownBy(() -> lifecycleService.transition(app.appId(),
                    new TenantLifecycleService.LifecycleRequest("delete", "it",
                            approver, APPROVER_PASSWORD)))
                    .isInstanceOf(com.newcoupon.common.BizException.class)
                    .hasMessageContaining("法务保留");
        }
        jdbc.update("UPDATE open_app SET legal_hold=0 WHERE app_id=?", app.appId());

        // 密码错 → 4002（统一文案，不区分"用户不存在/停用/密码错"——防枚举）
        try (var ctx = new NamedOperator("it-admin")) {
            assertThatThrownBy(() -> lifecycleService.transition(app.appId(),
                    new TenantLifecycleService.LifecycleRequest("delete", "it",
                            approver, "wrong-password")))
                    .isInstanceOf(com.newcoupon.common.BizException.class)
                    .hasMessageContaining("第二管理员复核未通过");
        }

        // 双人齐备（具名发起+正确第二人）→ DELETED；数据保留（逻辑死）
        try (var ctx = new NamedOperator("it-admin")) {
            lifecycleService.transition(app.appId(),
                    new TenantLifecycleService.LifecycleRequest("delete", "it",
                            approver, APPROVER_PASSWORD));
        }
        assertThat(openAppRepository.findApp(app.appId()).orElseThrow().lifecycle().name())
                .isEqualTo("DELETED");
        assertThat(eventMapper.selectByApp(app.appId()))
                .last().extracting(OpenAppLifecycleEventPO::getAction).isEqualTo("delete");
    }

    // ------------------------------------------------------------------
    // ⑥ 数据导出：一次性 + SHA-256
    // ------------------------------------------------------------------

    @Test
    @DisplayName("data-export：首次 200 且 X-Data-Sha256 与体一致；二次 4002；CLOSED 后拒")
    void dataExportOneShotWithSha256() throws Exception {
        // 应用 A：有真实模板与发放数据，验证一次性与哈希
        CreatedApp app = createProdApp();
        long userId = System.nanoTime();
        long templateId = createTemplateAndReceiveOne(app.appId(), userId);

        ResponseEntity<byte[]> first = adminGetBytes("/api/apps/" + app.appId() + "/data-export");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        byte[] body = first.getBody();
        String bodyJson = new String(body, StandardCharsets.UTF_8);
        assertThat(bodyJson)
                .contains("\"appId\":\"" + app.appId() + "\"")
                .contains(String.valueOf(templateId))
                .contains("\"issuedCountByTemplate\"");
        assertThat(first.getHeaders().getFirst("X-Data-Sha256"))
                .isEqualTo(sha256Hex(body));

        // 幂等重放不适用：导出是一次性闸门（事件表 data_export 行），第二次必 4002
        ResponseEntity<String> second = rest.exchange("/api/apps/" + app.appId() + "/data-export",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(second.getBody()).contains("4002").contains("一次性");

        // 应用 B：走完注销（CLOSED）后导出被状态拒绝——导出必须在注销完成前
        CreatedApp closed = createProdApp();
        lifecycleService.transition(closed.appId(),
                new TenantLifecycleService.LifecycleRequest("close", "it", null, null));
        lifecycleService.transition(closed.appId(),
                new TenantLifecycleService.LifecycleRequest("confirm_close", "it", null, null));
        ResponseEntity<String> refused = rest.exchange("/api/apps/" + closed.appId() + "/data-export",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(refused.getBody()).contains("4002").contains("注销");
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        return headers;
    }

    private static String sha256Hex(byte[] body) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    // ------------------------------------------------------------------
    // ⑦ 沙箱：券能发、钱不动
    // ------------------------------------------------------------------

    @Test
    @DisplayName("沙箱：sbx- 前缀建 app 即 SANDBOX 环境 + SANDBOX_ACTIVE；建券/领/核销照常；退款与预算懒建 4002")
    void sandboxIssuesCoupons_moneyBlocked() {
        // sbx- 前缀约定：不传 environment 参数也按沙箱处理
        String appId = unique("sbx-it");
        OpenAppService.AppWithSecret created = openAppService.create(new CreateAppRequest(
                appId, "沙箱验证券商", 0, 0, "it@example.com"));
        assertThat(created.environment()).isEqualTo("SANDBOX");
        assertThat(created.lifecycleStatus()).isEqualTo("SANDBOX_ACTIVE");
        assertThat(openAppRepository.findApp(appId).orElseThrow().sandbox()).isTrue();

        long userId = System.nanoTime();
        long templateId = createTemplateAndReceiveOne(appId, userId);

        // 发放/核销照常（沙箱的价值就是全链路联调）；分账行也照落（账单层负责过滤）
        String usageNo = lockAndConfirm(appId, templateId);
        assertThat(shareDetailMapper.selectPositiveByUsageNo(usageNo)).isNotEmpty();

        // 资金边界①：真实退款 4002（沙箱没有真钱可退）
        assertThatThrownBy(() -> tradeAppService.refund(new RefundRequest(usageNo, unique("RF"), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("沙箱环境不允许资金操作")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        // 资金边界②：预算账户懒建 4002（沙箱不发真钱账本——想测发放就别开 budget_enabled）。
        // 活动必须建在沙箱归属下：预算账户的归属取 activity.app_id（ensureAccount 的语义），
        // 建在默认归属（内置应用）上会让守卫看到 PROD 而放行
        long budgetTemplateId;
        try (var ctx = withAppContext(appId)) {
            com.newcoupon.application.dto.ActivityResponse activity = activityAppService.create(
                    new com.newcoupon.application.dto.ActivityCreateRequest(
                            "沙箱预算活动", 10000L, true,
                            LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), "it"));
            TemplateAppService.CreatedTemplate template = templateAppService.create(
                    new TemplateCreateRequest("沙箱预算券", activity.id(), 1, 2000L, null, 10000L,
                            10L, 1, 1, LocalDateTime.now().minusDays(1),
                            LocalDateTime.now().plusDays(30), null, 0L, 0));
            templateAppService.publish(template.id());
            budgetTemplateId = template.id();
        }
        assertThatThrownBy(() -> {
            try (var ctx = withAppContext(appId)) {
                receiveAppService.receive(new ReceiveRequest(unique("sbx-budget"), userId, budgetTemplateId));
            }
        })
                .isInstanceOf(BizException.class)
                .hasMessageContaining("沙箱环境不允许资金操作")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    // ------------------------------------------------------------------
    // ⑧ 日账单跳过沙箱模板
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DailyBill 跳过沙箱模板：沙箱核销有分账行，但当日模板账单无该模板行")
    void dailyBillSkipsSandboxTemplates() {
        String appId = unique("sbx-it");
        openAppService.create(new CreateAppRequest(appId, "沙箱账单验证券商", 0, 0, "it@example.com"));

        long userId = System.nanoTime();
        long templateId = createTemplateAndReceiveOne(appId, userId);
        String usageNo = lockAndConfirm(appId, templateId);

        // 前提自证：分账行确实存在（否则"账单无行"就是空断言）
        assertThat(shareDetailMapper.selectPositiveByUsageNo(usageNo))
                .as("沙箱核销的分账行照常落库（账单层过滤，不是核销层拒绝）")
                .isNotEmpty();

        dailyBillService.generateBill(LocalDate.now());
        assertThat(billMapper.selectByDate(LocalDate.now()))
                .as("沙箱模板不进真账单——假钱进真账是资损口径事故")
                .noneMatch(bill -> bill.getTemplateId() == templateId);
    }

    // ------------------------------------------------------------------
    // 业务小工具
    // ------------------------------------------------------------------

    /** 在指定 appId 的归属下建模板并发放一张券，返回模板 id（用完记得关上下文） */
    private long createTemplateAndReceiveOne(String appId, long userId) {
        long templateId;
        try (var ctx = withAppContext(appId)) {
            TemplateAppService.CreatedTemplate created = templateAppService.create(
                    new TemplateCreateRequest("生命周期验证券-" + appId, 990001L, 1, 2000L, null,
                            10000L, 10L, 1, 1, LocalDateTime.now().minusDays(1),
                            LocalDateTime.now().plusDays(30), null, 0L, 0));
            templateAppService.publish(created.id());
            templateId = created.id();
            ReceiveResult rcv = receiveAppService.receive(
                    new ReceiveRequest(unique("rcv"), userId, templateId));
            assertThat(rcv.couponId()).as("沙箱/生产发放照常").isPositive();
        }
        return templateId;
    }

    /** 锁券+核销（两行商品，行级分账），返回 usageNo。自建独立 userId——模板 perUserLimit=1，
     *  复用外层的 userId 会撞"已达限领上限"，这里的用途是核销分账而不是领券语义本身 */
    private String lockAndConfirm(String appId, long templateId) {
        long userId = System.nanoTime();
        try (var ctx = withAppContext(appId)) {
            ReceiveResult rcv = receiveAppService.receive(
                    new ReceiveRequest(unique("rcv2"), userId, templateId));
            var lock = tradeAppService.lock(new com.newcoupon.application.dto.TradeDtos.LockRequest(
                    unique("ORD"), userId, rcv.couponId()));
            tradeAppService.confirm(new com.newcoupon.application.dto.TradeDtos.ConfirmRequest(
                    lock.usageNo(), null, List.of(
                            new CalculateRequest.OrderItem("SKU_A", "CAT1", 6000L, 2, "L1"),
                            new CalculateRequest.OrderItem("SKU_B", "CAT1", 4000L, 2, "L2"))));
            return lock.usageNo();
        }
    }

    /** 模拟开放面调用方身份（AppOwnership 按它归属新资源/校验越权）——用完即清，防线程串味 */
    private ContextHandle withAppContext(String appId) {
        RequestContext.set(new RequestContext(appId, "it-lifecycle", Long.MAX_VALUE, false));
        return new ContextHandle();
    }

    /** try-with-resources 的把手：close 不抛受检异常（AutoCloseable 会，逼调用方乱接 throws） */
    private static final class ContextHandle implements AutoCloseable {
        @Override
        public void close() {
            RequestContext.clear();
        }
    }
}
