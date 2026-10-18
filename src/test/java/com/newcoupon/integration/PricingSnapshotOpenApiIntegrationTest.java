package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.common.crypto.OpenApiSignature;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 环 2 价格快照 · 开放面 HTTP 验收（2026-10-18）。
 *
 * <p>直接 service 集成测试证明不了两件事：calculate 的响应字段能否经 Jackson 出去、
 * {@code OpenApiIdempotentFilter} 的 24h 响应缓存与 digest 复用如何交互。本类走真实
 * 签名 + X-Client-Token + /open/v1 全链路：
 * <ol>
 *   <li>calculate 返回 pricingId；同 body 换 token 仍复用同一 ID；换订单号生成新 ID；</li>
 *   <li>lock/confirm 只带 pricingId（不带 items）、中途改模板，核销金额保持不变。</li>
 * </ol>
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168",
                "coupon.trade.pricing.enabled=true"
        })
class PricingSnapshotOpenApiIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String APP_ID = "t-pricing-http";
    private static final long USER_ID = 88_001L;
    private static final String CLIENT_TOKEN_HEADER = "X-Client-Token";

    @Autowired private OpenAppService appService;
    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CachedTemplateReader cachedTemplateReader;
    @Autowired private DataSource dataSource;

    private static String secret;
    private static long templateId;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() throws Exception {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        secret = appService.create(new CreateAppRequest(APP_ID, "价格快照 HTTP 测试", null, null, null))
                .secret();
        ResponseEntity<String> created = post("/open/v1/templates", """
                {"name":"HTTP价格快照券","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":5000,"totalCount":100,"perUserLimit":5,
                 "validType":2,"validDays":7}""", "ps-http-create-1");
        templateId = readLong(created, "/data/id");
        post("/open/v1/templates/" + templateId + "/publish", "", "ps-http-publish-1");
    }

    /** 每个用例领自己的券，避免“前一个用例把券核销了”导致执行顺序依赖 */
    private long receiveCoupon(String requestId) throws Exception {
        ResponseEntity<String> received = post("/open/v1/coupons/receive", """
                {"requestId":"%s","userId":%d,"templateId":%d}"""
                .formatted(requestId, USER_ID, templateId), "token-" + requestId);
        return readLong(received, "/data/couponId");
    }

    @Test
    @DisplayName("H1: calculate 返回 pricingId；同 body 换 token 复用；不同订单号生成新 ID")
    void calculateReturnsAndReusesPricingIdOverHttp() throws Exception {
        long couponId = receiveCoupon("ps-http-h1");
        String body = calculateBody("ORD-HTTP-PS-1", couponId);

        ResponseEntity<String> first = post("/open/v1/calculate", body, "ps-http-calc-1");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        String firstId = readText(first, "/data/pricingId");
        assertThat(firstId).isNotBlank();

        // 换 token、同 body：绕过响应缓存，由快照服务按摘要复用
        ResponseEntity<String> second = post("/open/v1/calculate", body, "ps-http-calc-2");
        assertThat(readText(second, "/data/pricingId")).isEqualTo(firstId);

        // 同 token 重放：命中幂等缓存，逐字节一致
        ResponseEntity<String> replay = post("/open/v1/calculate", body, "ps-http-calc-1");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");

        // 换订单号：新快照、新 ID
        ResponseEntity<String> otherOrder = post("/open/v1/calculate",
                calculateBody("ORD-HTTP-PS-2", couponId), "ps-http-calc-3");
        assertThat(readText(otherOrder, "/data/pricingId")).isNotBlank().isNotEqualTo(firstId);
    }

    @Test
    @DisplayName("H2: HTTP 全链路 lock→改模板→confirm 只带 pricingId，金额不变")
    void lockAndConfirmOnlyPricingIdOverHttp() throws Exception {
        long couponId = receiveCoupon("ps-http-h2");
        String orderNo = "ORD-HTTP-PS-3";
        ResponseEntity<String> calc = post("/open/v1/calculate",
                calculateBody(orderNo, couponId), "ps-http-e2e-calc-1");
        String pricingId = readText(calc, "/data/pricingId");

        ResponseEntity<String> locked = post("/open/v1/coupons/lock", """
                {"orderNo":"%s","userId":%d,"couponId":%d,"pricingId":"%s"}"""
                .formatted(orderNo, USER_ID, couponId, pricingId), "ps-http-e2e-lock-1");
        assertThat(locked.getStatusCode().value()).isEqualTo(200);
        String usageNo = readText(locked, "/data/usageNo");

        new JdbcTemplate(dataSource).update(
                "UPDATE coupon_template SET face_value = 9000 WHERE id = ?", templateId);
        cachedTemplateReader.invalidate(templateId);

        ResponseEntity<String> confirmed = post("/open/v1/coupons/confirm", """
                {"usageNo":"%s","pricingId":"%s"}"""
                .formatted(usageNo, pricingId), "ps-http-e2e-confirm-1");

        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(readLong(confirmed, "/data/discountAmt")).isEqualTo(1000L);
    }

    // ------------------------------------------------------------------

    private String calculateBody(String orderNo, long couponId) {
        return """
                {"userId":%d,
                 "items":[{"skuId":"SKU1","categoryId":"CAT1","price":10000,"quantity":1,"lineNo":"L1"}],
                 "couponIds":[%d],
                 "orderNo":"%s"}"""
                .formatted(USER_ID, couponId, orderNo);
    }

    private ResponseEntity<String> post(String path, String body, String clientToken) {
        HttpHeaders headers = signedHeaders(HttpMethod.POST, path, body);
        headers.set(CLIENT_TOKEN_HEADER, clientToken);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders signedHeaders(HttpMethod method, String path, String body) {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String bodySha = OpenApiSignature.bodySha256(
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        String canonical = OpenApiSignature.canonicalString(
                method.name(), path, OpenApiSignature.canonicalQuery(null), bodySha, APP_ID,
                Long.parseLong(ts), nonce);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", OpenApiSignature.SCHEME + " Credential=" + APP_ID
                + ", Timestamp=" + ts
                + ", Nonce=" + nonce
                + ", Signature=" + OpenApiSignature.sign(secret, canonical));
        return headers;
    }

    private long readLong(ResponseEntity<String> response, String pointer) throws Exception {
        JsonNode node = objectMapper.readTree(response.getBody()).at(pointer);
        assertThat(node.isMissingNode() || node.isNull())
                .as("响应里找不到 " + pointer + "：" + response.getBody()).isFalse();
        return node.asLong();
    }

    private String readText(ResponseEntity<String> response, String pointer) throws Exception {
        JsonNode node = objectMapper.readTree(response.getBody()).at(pointer);
        assertThat(node.isMissingNode() || node.isNull())
                .as("响应里找不到 " + pointer + "：" + response.getBody()).isFalse();
        return node.asText();
    }
}
