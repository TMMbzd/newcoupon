package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.common.crypto.OpenApiSignature;
import com.newcoupon.domain.open.OpenApp;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * S1 第 4 步：开放面认证（RQ-3/RQ-4）——<b>红绿灯</b>。
 *
 * <p>红：本类先于 {@code OpenApiAuthFilter} 写就——此时 {@code /open/v1/**} 是<b>裸奔</b>的
 * （Controller 已挂上开放面前缀，但没有任何鉴权），所以正向用例绿、六条反例全红。
 * 这正是本类存在的意义：**证明这些用例真的在测认证**，而不是测了个恒真条件。
 *
 * <p>六条反例覆盖认证链的每一个拒绝点：
 * <ol>
 *   <li>缺签名头（6001）</li>
 *   <li>应用不存在（6002）</li>
 *   <li>应用已停用（6002）</li>
 *   <li>时间戳超窗（6004）</li>
 *   <li>签名不匹配（6003）——含"改了 body 但签名没变"的完整性反例</li>
 *   <li>nonce 重放（6005）</li>
 * </ol>
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class OpenApiAuthIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String APP_ID = "t-sign";
    private static final String GHOST_APP_ID = "t-sign-ghost";
    private static final String DISABLED_APP_ID = "t-sign-disabled";

    @Autowired
    private OpenAppService appService;

    @Autowired
    private TestRestTemplate rest;

    /** 只在首个用例前建一次（@BeforeEach + CAS：不用 @BeforeAll 是因为它需要 PER_CLASS 实例，
     *  而 PER_CLASS 会让测试实例先于容器启动被创建——Spring 上下文提前加载，
     *  报 "Mapped port can only be obtained after the container is started"。实测踩坑） */
    private static final java.util.concurrent.atomic.AtomicBoolean PROVISIONED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static String secret;
    private static String disabledSecret;

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        secret = appService.create(new CreateAppRequest(APP_ID, "签名测试接入方", null, null, null)).secret();
        disabledSecret = appService.create(
                new CreateAppRequest(DISABLED_APP_ID, "已停用的接入方", null, null, null)).secret();
        appService.setStatus(DISABLED_APP_ID, OpenApp.STATUS_DISABLED);
    }

    // ------------------------------------------------------------------
    // 正向：签名正确 → 到达业务
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正向：签名正确的开放面请求 → 到达业务并成功")
    void validSignature_reachesBusiness() {
        ResponseEntity<String> r = call(HttpMethod.GET, "/open/v1/coupons", "userId=123", null,
                APP_ID, secret, System.currentTimeMillis(), newNonce());

        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":0");
    }

    // ------------------------------------------------------------------
    // 六条反例：认证链的每一个拒绝点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("反例①：没有 Authorization 头 → 401 + 6001")
    void missingHeader_rejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.exchange("/open/v1/coupons?userId=123", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("6001");
    }

    @Test
    @DisplayName("反例②：应用不存在 → 401 + 6002")
    void unknownApp_rejected() {
        ResponseEntity<String> r = call(HttpMethod.GET, "/open/v1/coupons", "userId=123", null,
                GHOST_APP_ID, "any-secret", System.currentTimeMillis(), newNonce());

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("6002");
    }

    @Test
    @DisplayName("反例③：应用已停用（密钥是对的，但门关着）→ 401 + 6002")
    void disabledApp_rejected() {
        ResponseEntity<String> r = call(HttpMethod.GET, "/open/v1/coupons", "userId=123", null,
                DISABLED_APP_ID, disabledSecret, System.currentTimeMillis(), newNonce());

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("6002");
    }

    @Test
    @DisplayName("反例④：时间戳超出 ±5 分钟窗口 → 401 + 6004")
    void staleTimestamp_rejected() {
        long tenMinutesAgo = System.currentTimeMillis() - 10 * 60 * 1000L;
        ResponseEntity<String> r = call(HttpMethod.GET, "/open/v1/coupons", "userId=123", null,
                APP_ID, secret, tenMinutesAgo, newNonce());
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("6004");

        long tenMinutesAhead = System.currentTimeMillis() + 10 * 60 * 1000L;
        ResponseEntity<String> future = call(HttpMethod.GET, "/open/v1/coupons", "userId=123", null,
                APP_ID, secret, tenMinutesAhead, newNonce());
        assertThat(future.getStatusCode().value())
                .as("未来时间戳同样拒绝——否则攻击者用'很远的未来'就能绕过窗口")
                .isEqualTo(401);
        assertThat(future.getBody()).contains("6004");
    }

    @Test
    @DisplayName("反例⑤：签名不匹配 → 401 + 6003")
    void tamperedSignature_rejected() {
        HttpHeaders headers = signedHeaders(HttpMethod.GET.name(), "/open/v1/coupons", "userId=123",
                null, APP_ID, secret, System.currentTimeMillis(), newNonce());
        String auth = headers.getFirst("Authorization");

        // 篡改末位 hex 字符——**必须保证真的改了**：写死"改成 0"时若末位本来就是 0（1/16 概率），
        // 签名依然合法、用例假红。第一版就是这么写的，被跑出来的失败抓了个正着。
        char last = auth.charAt(auth.length() - 1);
        char replacement = last == '0' ? '1' : '0';
        headers.set("Authorization", auth.substring(0, auth.length() - 1) + replacement);

        ResponseEntity<String> r = rest.exchange("/open/v1/coupons?userId=123", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("6003");
    }

    @Test
    @DisplayName("反例⑤-补：签名合法但 body 被改（完整性）→ 401 + 6003")
    void tamperedBody_rejected() {
        String original = "{\"requestId\":\"req-1\",\"userId\":123,\"templateId\":1}";
        String tampered = original.replace("\"userId\":123", "\"userId\":999");

        // 用 original 签名，却发送 tampered——签名保的是"内容没被改过"
        HttpHeaders headers = signedHeaders(HttpMethod.POST.name(), "/open/v1/coupons/receive", null,
                original, APP_ID, secret, System.currentTimeMillis(), newNonce());

        ResponseEntity<String> r = rest.exchange("/open/v1/coupons/receive", HttpMethod.POST,
                new HttpEntity<>(tampered, headers), String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("6003");
    }

    @Test
    @DisplayName("反例⑥：同一个 nonce 用第二次 → 401 + 6005（时间窗内的重放）")
    void replayedNonce_rejected() {
        String nonce = newNonce();
        long ts = System.currentTimeMillis();

        ResponseEntity<String> first = call(HttpMethod.GET, "/open/v1/coupons", "userId=123", null,
                APP_ID, secret, ts, nonce);
        assertThat(first.getStatusCode().value()).as("第一次应当通过").isEqualTo(200);

        ResponseEntity<String> replay = call(HttpMethod.GET, "/open/v1/coupons", "userId=123", null,
                APP_ID, secret, ts, nonce);
        assertThat(replay.getStatusCode().value()).as("原样重放必须被拒").isEqualTo(401);
        assertThat(replay.getBody()).contains("6005");
    }

    @Test
    @DisplayName("反例⑦：未实现的接口版本 /open/v2/** → 400 + 6010（不是 404）")
    void unsupportedVersion_rejected() {
        ResponseEntity<String> r = call(HttpMethod.GET, "/open/v2/coupons", "userId=123", null,
                APP_ID, secret, System.currentTimeMillis(), newNonce());

        assertThat(r.getStatusCode().value())
                .as("404 无法区分'版本不存在'和'接口写错'，所以由入口直接拒")
                .isEqualTo(400);
        assertThat(r.getBody()).contains("6010");
    }

    // ------------------------------------------------------------------
    // 辅助：构造一个合法签名的请求
    // ------------------------------------------------------------------

    private ResponseEntity<String> call(HttpMethod method, String path, String query, String body,
                                        String appId, String secret, long timestamp, String nonce) {
        HttpHeaders headers = signedHeaders(method.name(), path, query, body, appId, secret, timestamp, nonce);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        String url = query == null || query.isEmpty() ? path : path + "?" + query;
        return rest.exchange(url, method, entity, String.class);
    }

    private HttpHeaders signedHeaders(String method, String path, String query, String body,
                                      String appId, String secret, long timestamp, String nonce) {
        String bodySha = OpenApiSignature.bodySha256(
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        String canonical = OpenApiSignature.canonicalString(
                method, path, OpenApiSignature.canonicalQuery(query), bodySha, appId, timestamp, nonce);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", OpenApiSignature.SCHEME + " Credential=" + appId
                + ", Timestamp=" + timestamp
                + ", Nonce=" + nonce
                + ", Signature=" + OpenApiSignature.sign(secret, canonical));
        return headers;
    }

    private static String newNonce() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
