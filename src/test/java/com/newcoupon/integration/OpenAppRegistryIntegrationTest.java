package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.AppView;
import com.newcoupon.application.service.OpenAppService.AppWithSecret;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.OpenAppService.SecretView;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.crypto.SecretCipher;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * S1 第 1 步：接入方注册与生命周期（RQ-1 / RQ-2）——<b>红绿灯</b>。
 *
 * <p>红：本类先于实现写就，方法体抛 UnsupportedOperationException；绿：实现后全绿。
 *
 * <p>钉住的四条关键语义：
 * <ol>
 *   <li><b>SK 只显示一次</b>——创建响应里有明文，库里只有密文，且密文可以解回明文
 *       （证明是"加密"不是"哈希"：HMAC 验签需要原密钥，哈希不可逆就用不了）；</li>
 *   <li><b>轮换期</b>——旧密钥不是立刻作废，而是在轮换期内继续可用（接入方换密钥要发版）；</li>
 *   <li><b>过期即失效</b>——轮换期一到（用零窗口模拟），旧密钥立刻退出可用集合；</li>
 *   <li><b>内置应用</b>——迁移写入的 app_id='1' 必须存在（存量模板/券的归属方）。</li>
 * </ol>
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        // 固定主密钥：测试不依赖 dev 配置，也避免"主密钥未配置→启动失败"的 fail-fast 误伤
        "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
        "coupon.open.secret-rotation-hours=168",     // 7 天，与默认一致；显式写出便于断言
        "coupon.admin.token=test-admin-token"        // 管理面接口用例要过 AdminTokenFilter
})
class OpenAppRegistryIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private OpenAppService service;

    @Autowired
    private OpenAppRepository repository;

    @Autowired
    private SecretCipher cipher;

    // ------------------------------------------------------------------
    // 迁移与种子
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V14 迁移：内置应用 app_id='1' 存在且启用（存量模板/券的归属方）")
    void builtinApp_existsFromMigration() {
        AppView app = service.get(OpenApp.BUILTIN_APP_ID);
        assertThat(app.appName()).contains("内置应用");
        assertThat(app.status()).isEqualTo(OpenApp.STATUS_ENABLED);
        assertThat(app.qpsLimit()).as("内置应用不限流（0=不限）").isZero();
        assertThat(app.secrets()).as("内置应用不走签名面，不配密钥").isEmpty();
    }

    // ------------------------------------------------------------------
    // 注册（RQ-1）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("注册：返回明文 SK（只此一次），库里只存密文，且密文可解回明文")
    void create_returnsPlaintextOnce_butStoresCipher() {
        AppWithSecret created = service.create(new CreateAppRequest(
                "t-create", "注册验证券商", 50, 5000, "ops@example.com"));

        assertThat(created.appId()).isEqualTo("t-create");
        assertThat(created.secret()).as("SK 是 32 字节随机数的 Base64URL（43 字符）").hasSize(43);

        List<OpenAppRepository.SecretRecord> stored = repository.findAllSecrets("t-create");
        assertThat(stored).hasSize(1);
        String cipherText = stored.get(0).cipher();
        assertThat(cipherText)
                .as("库里绝不能出现明文 SK")
                .isNotEqualTo(created.secret())
                .doesNotContain(created.secret());
        assertThat(cipher.decrypt(cipherText))
                .as("必须可解密还原——HMAC 验签要用原密钥，所以这里只能是加密不能是哈希")
                .isEqualTo(created.secret());

        AppView view = service.get("t-create");
        assertThat(view.secrets()).hasSize(1);
        SecretView secret = view.secrets().get(0);
        assertThat(secret.usable()).isTrue();
        assertThat(secret.status()).isEqualTo(OpenAppRepository.SecretRecord.STATUS_CURRENT);
    }

    @Test
    @DisplayName("注册：未指定配额时落建表默认值（qps 100 / 日配额 100 万）")
    void create_appliesDefaultsWhenQuotaOmitted() {
        AppWithSecret created = service.create(new CreateAppRequest("t-default", "默认配额应用", null, null, null));
        assertThat(created.qpsLimit()).isEqualTo(100);
        assertThat(created.dailyQuota()).isEqualTo(1_000_000);
        assertThat(created.status()).isEqualTo(OpenApp.STATUS_ENABLED);
    }

    @Test
    @DisplayName("注册：未提供 appId 时系统生成（app- + 12 位十六进制，满足格式约束）")
    void create_withoutAppId_generatesOne() {
        AppWithSecret created = service.create(new CreateAppRequest(null, "自动生成", null, null, null));

        assertThat(created.appId()).matches(OpenApp.APP_ID_PATTERN).startsWith("app-").hasSize(16);
        assertThat(service.get(created.appId()).appName()).isEqualTo("自动生成");
    }

    @Test
    @DisplayName("注册：appId 重复 → 业务错误（由唯一索引终审，不是先查后插）")
    void create_duplicateAppId_rejected() {
        service.create(new CreateAppRequest("t-dup", "第一个", null, null, null));

        assertThatThrownBy(() -> service.create(new CreateAppRequest("t-dup", "第二个", null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    @DisplayName("注册：appId 格式非法 → 业务错误")
    void create_invalidAppId_rejected() {
        for (String bad : new String[]{"Ab", "has space", "UPPER", "含中文", "-leading", "a"}) {
            assertThatThrownBy(() -> service.create(new CreateAppRequest(bad, "非法", null, null, null)))
                    .as("非法 appId: " + bad)
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    @DisplayName("注册：appName 缺失 → 业务错误")
    void create_blankName_rejected() {
        assertThatThrownBy(() -> service.create(new CreateAppRequest("t-noname", "  ", null, null, null)))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("查询不存在的应用 → 业务错误（管理面按'查无此应用'处理）")
    void get_unknownApp_rejected() {
        assertThatThrownBy(() -> service.get("t-not-exist"))
                .isInstanceOf(BizException.class);
    }

    // ------------------------------------------------------------------
    // 生命周期与轮换（RQ-1 / RQ-2）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("启停：停用后 status=0（数据与密钥保留，可恢复）")
    void disable_thenGet_showsDisabled() {
        service.create(new CreateAppRequest("t-disable", "待停用", null, null, null));

        service.setStatus("t-disable", OpenApp.STATUS_DISABLED);
        assertThat(service.get("t-disable").status()).isEqualTo(OpenApp.STATUS_DISABLED);
        assertThat(service.get("t-disable").secrets()).as("停用不动密钥").hasSize(1);

        service.setStatus("t-disable", OpenApp.STATUS_ENABLED);
        assertThat(service.get("t-disable").status()).isEqualTo(OpenApp.STATUS_ENABLED);
    }

    @Test
    @DisplayName("停用不存在的应用 → 业务错误")
    void disable_unknownApp_rejected() {
        assertThatThrownBy(() -> service.setStatus("t-ghost", OpenApp.STATUS_DISABLED))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("轮换：新 SK 生效，旧 SK 在轮换期内仍可用（不能一刀切，接入方要发版）")
    void rotate_keepsOldSecretUsableWithinWindow() {
        AppWithSecret first = service.create(new CreateAppRequest("t-rotate", "轮换应用", null, null, null));

        LocalDateTime before = LocalDateTime.now();
        AppWithSecret second = service.rotate("t-rotate");

        assertThat(second.secret()).as("轮换必须签发新的 SK").isNotEqualTo(first.secret());

        List<SecretView> secrets = service.get("t-rotate").secrets();
        assertThat(secrets).hasSize(2);
        assertThat(secrets.stream().filter(SecretView::usable).count())
                .as("轮换期内新旧两把都能验签")
                .isEqualTo(2);
        assertThat(secrets.stream().filter(s -> s.status() == OpenAppRepository.SecretRecord.STATUS_CURRENT).count())
                .as("当前密钥有且只有一把")
                .isEqualTo(1);

        SecretView rotating = secrets.stream()
                .filter(s -> s.status() == OpenAppRepository.SecretRecord.STATUS_ROTATING)
                .findFirst().orElseThrow();
        assertThat(rotating.expireTime())
                .as("轮换期默认 168 小时（7 天）")
                .isAfter(before.plusHours(167))
                .isBefore(LocalDateTime.now().plusHours(169));
    }

    @Test
    @DisplayName("轮换（零窗口）：轮换期一到，旧密钥立刻退出可用集合")
    void rotate_zeroWindow_oldSecretImmediatelyUnusable() {
        AppWithSecret first = service.create(new CreateAppRequest("t-rotate-zero", "立即失效", null, null, null));

        AppWithSecret second = service.rotate("t-rotate-zero", Duration.ZERO);

        List<SecretView> secrets = service.get("t-rotate-zero").secrets();
        assertThat(secrets).hasSize(2);

        List<SecretView> usable = secrets.stream().filter(SecretView::usable).toList();
        assertThat(usable).as("零窗口轮换后，只剩新签发的那把可用").hasSize(1);

        String usableCipher = repository.findAllSecrets("t-rotate-zero").stream()
                .filter(r -> r.id() == usable.get(0).id())
                .findFirst().orElseThrow().cipher();
        assertThat(cipher.decrypt(usableCipher))
                .as("可用的那把 = 本次轮换签发的新 SK（不是旧的）")
                .isEqualTo(second.secret())
                .isNotEqualTo(first.secret());
    }

    @Test
    @DisplayName("轮换不存在的应用 → 业务错误")
    void rotate_unknownApp_rejected() {
        assertThatThrownBy(() -> service.rotate("t-rotate-ghost"))
                .isInstanceOf(BizException.class);
    }

    // ------------------------------------------------------------------
    // 管理面 HTTP 接口（RQ-1 的运维入口；@RequestMapping 用 RANDOM_PORT 才测得到真实过滤链）
    // ------------------------------------------------------------------

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("管理面：无令牌注册应用 → 401 + 7002（管理面 fail-closed）")
    void adminCreate_withoutToken_rejected() {
        ResponseEntity<String> r = rest.postForEntity("/api/apps",
                jsonEntity("""
                        {"appId":"t-api-unauth","appName":"无令牌"}""", null), String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("7002");
    }

    @Test
    @DisplayName("管理面：带令牌注册 → 200 且返回明文 SK（只此一次的机会）")
    void adminCreate_withToken_returnsPlaintextSecret() {
        ResponseEntity<String> r = postAdmin("/api/apps", """
                {"appId":"t-api-create","appName":"接口验证券商","qpsLimit":10,"dailyQuota":1000,"contact":"ops"}""");

        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":0").contains("\"appId\":\"t-api-create\"").contains("\"secret\"");
        assertThat(r.getBody()).contains("\"qpsLimit\":10").contains("\"dailyQuota\":1000");
    }

    @Test
    @DisplayName("管理面：详情接口返回密钥状态但**不回吐明文 SK**")
    void adminGet_hidesPlaintextSecret() {
        postAdmin("/api/apps", """
                {"appId":"t-api-get","appName":"详情验证券商"}""");

        ResponseEntity<String> r = rest.getForEntity("/api/apps/t-api-get", String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"appId\":\"t-api-get\"").contains("\"usable\":true");
        assertThat(r.getBody())
                .as("查询接口不得回吐密钥明文（明文只在创建/轮换那一次给）")
                .doesNotContain("\"secret\":\"");
    }

    @Test
    @DisplayName("管理面：轮换 → 返回新 SK；详情里能看到'当前 + 轮换期'两条密钥")
    void adminRotate_returnsNewSecret() {
        postAdmin("/api/apps", """
                {"appId":"t-api-rotate","appName":"轮换验证券商"}""");

        ResponseEntity<String> rotated = postAdmin("/api/apps/t-api-rotate/rotate", "");
        assertThat(rotated.getStatusCode().value()).as(String.valueOf(rotated.getBody())).isEqualTo(200);
        assertThat(rotated.getBody()).contains("\"secret\"");

        ResponseEntity<String> detail = rest.getForEntity("/api/apps/t-api-rotate", String.class);
        assertThat(detail.getBody())
                .as("轮换后密钥记录应为两条：status=1（当前）与 status=2（轮换期）")
                .contains("\"status\":1").contains("\"status\":2");
    }

    @Test
    @DisplayName("管理面：停用不存在的应用 → 业务错误码（不是 500）")
    void adminDisable_unknownApp_businessError() {
        ResponseEntity<String> r = postAdmin("/api/apps/t-api-ghost/status", """
                {"status":0}""");
        assertThat(r.getStatusCode().value()).as("业务失败走统一 Result（HTTP 200 + 业务码）")
                .isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":1000");
    }

    private ResponseEntity<String> postAdmin(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", "test-admin-token");
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpEntity<String> jsonEntity(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("X-Admin-Token", token);
        }
        return new HttpEntity<>(body, headers);
    }
}
