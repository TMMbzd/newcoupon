package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.common.crypto.OpenApiSignature;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * S1 第 6 步：接入层幂等（RQ-5）——<b>红绿灯</b>。
 *
 * <p>红：本类先于 {@code OpenApiIdempotentFilter} 写就——此时写接口完全不看 {@code X-Client-Token}，
 * 所以"同 token 重放返回同一结果""异参 6008""缺失 6012"三条全红。
 *
 * <p><b>走的是真实发券链路</b>（建券 → 发布 → 领券 → 重放 → 查券包），不是打桩：
 * 幂等最有价值的断言恰恰是"重放**没有**多发一张券"——那必须落到券包的真实数据上才算数。
 *
 * <p>三个坑（设计文档 §4.6 预警）在这里转成用例：
 * <ol>
 *   <li>请求体哈希：不先读 body 就算哈希 → 恒为空 → "同 token 异参"永远判不出来；</li>
 *   <li>响应包装器忘了 {@code copyBodyToResponse()} → 客户端收到空 body；</li>
 *   <li>重放命中时若还调 {@code chain.doFilter} → 业务被重复执行。</li>
 * </ol>
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class OpenApiIdempotentIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String APP_ID = "t-idem";
    private static final long USER_ID = 77_001L;
    private static final String CLIENT_TOKEN_HEADER = "X-Client-Token";

    @Autowired
    private OpenAppService appService;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    private static String secret;
    private static Long templateId;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        secret = appService.create(new CreateAppRequest(APP_ID, "幂等测试接入方", null, null, null)).secret();

        // 真实链路：建券 → 发布（都走开放面 + clientToken）
        ResponseEntity<String> created = post("/open/v1/templates", """
                {"name":"幂等验证券","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":100,"perUserLimit":5,
                 "validType":2,"validDays":7}""", "idem-create-1");
        templateId = readLong(created, "/data/id");
        post("/open/v1/templates/" + templateId + "/publish", "", "idem-publish-1");
    }

    // ------------------------------------------------------------------
    // 正向：同 token 同参 → 同一个结果，且没有多发券
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同 token 同参重放 → 返回同一结果 + X-Idempotent-Replay，且**只发一张券**")
    void sameTokenSameParams_replaysAndDoesNotDoubleIssue() {
        String body = receiveBody("idem-run-1", USER_ID);

        ResponseEntity<String> first = post("/open/v1/coupons/receive", body, "tok-replay-1");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).as("首次必须成功").contains("\"code\":0");
        assertThat(first.getHeaders().getFirst("X-Idempotent-Replay"))
                .as("首次不是重放，不应带这个头").isNull();

        ResponseEntity<String> replay = post("/open/v1/coupons/receive", body, "tok-replay-1");

        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).as("重放必须逐字节返回同一结果").isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getFirst("X-Idempotent-Replay"))
                .as("必须明确告诉接入方'这是重放结果'").isEqualTo("true");

        // 最强断言：券包里有且只有一张券——重放没有真的再发一次
        ResponseEntity<String> wallet = get("/open/v1/coupons?userId=" + USER_ID);
        assertThat(wallet.getBody()).as("券包查询本身要能正常返回（响应包装器没吞掉 body）")
                .contains("\"code\":0");
        assertThat(couponCount(wallet.getBody())).as("重放不得多发券").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 反例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同 token 但参数不同 → 409 + 6008（token 是'这一次操作'的标识，不能换参数复用）")
    void sameTokenDifferentParams_conflict() {
        long otherUser = 77_002L;
        post("/open/v1/coupons/receive", receiveBody("idem-other-1", otherUser), "tok-conflict-1");

        // 同 token、换了 userId → 参数指纹不同，必须判冲突而不是回放
        ResponseEntity<String> conflict = post("/open/v1/coupons/receive",
                receiveBody("idem-other-2", otherUser + 1), "tok-conflict-1");

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody()).contains("6008");
    }

    @Test
    @DisplayName("写接口不带 clientToken → 400 + 6012")
    void missingClientToken_rejected() {
        String body = receiveBody("idem-notoken", USER_ID);
        HttpHeaders headers = signedHeaders(HttpMethod.POST, "/open/v1/coupons/receive", null, body);
        // 故意不设 X-Client-Token
        ResponseEntity<String> r = rest.exchange("/open/v1/coupons/receive", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).contains("6012");
    }

    @Test
    @DisplayName("读接口（GET）不要求 clientToken——幂等只约束写操作")
    void getRequest_doesNotRequireClientToken() {
        ResponseEntity<String> wallet = get("/open/v1/coupons?userId=" + USER_ID);

        assertThat(wallet.getStatusCode().value()).isEqualTo(200);
        assertThat(wallet.getBody()).contains("\"code\":0");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** userId 必须显式传：第一版写死成 USER_ID，结果"异参"用例实际给同一个用户又发了一张券，
     *  把"重放不得多发券"那条断言弄红——**用例自己的 bug 伪装成了实现的问题**。 */
    private String receiveBody(String requestId, long userId) {
        return "{\"requestId\":\"" + requestId + "\",\"userId\":" + userId
                + ",\"templateId\":" + templateId + "}";
    }

    private long couponCount(String walletJson) {
        try {
            JsonNode coupons = objectMapper.readTree(walletJson).path("data").path("coupons");
            return coupons.size();
        } catch (Exception e) {
            throw new AssertionError("券包响应无法解析: " + walletJson, e);
        }
    }

    private long readLong(ResponseEntity<String> response, String jsonPointer) {
        try {
            JsonNode node = objectMapper.readTree(response.getBody()).at(jsonPointer);
            assertThat(node.isMissingNode() || node.isNull())
                    .as("响应里找不到 " + jsonPointer + "：" + response.getBody()).isFalse();
            return node.asLong();
        } catch (Exception e) {
            throw new AssertionError("响应无法解析: " + response.getBody(), e);
        }
    }

    private ResponseEntity<String> post(String path, String body, String clientToken) {
        HttpHeaders headers = signedHeaders(HttpMethod.POST, path, null, body);
        if (clientToken != null) {
            headers.set(CLIENT_TOKEN_HEADER, clientToken);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String pathWithQuery) {
        int q = pathWithQuery.indexOf('?');
        String path = q < 0 ? pathWithQuery : pathWithQuery.substring(0, q);
        String query = q < 0 ? null : pathWithQuery.substring(q + 1);
        HttpHeaders headers = signedHeaders(HttpMethod.GET, path, query, null);
        return rest.exchange(pathWithQuery, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private HttpHeaders signedHeaders(HttpMethod method, String path, String query, String body) {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String bodySha = OpenApiSignature.bodySha256(
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        String canonical = OpenApiSignature.canonicalString(
                method.name(), path, OpenApiSignature.canonicalQuery(query), bodySha, APP_ID,
                Long.parseLong(ts), nonce);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", OpenApiSignature.SCHEME + " Credential=" + APP_ID
                + ", Timestamp=" + ts
                + ", Nonce=" + nonce
                + ", Signature=" + OpenApiSignature.sign(secret, canonical));
        return headers;
    }
}
