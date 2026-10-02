package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * S1 第 8 步：归属与越权防线（RQ-8）——<b>红绿灯</b>。
 *
 * <p>红：本类先于归属校验写就——此时 A 应用能读、能发布、能领 B 应用的一切资源（appId 只是个没人看的列）。
 *
 * <p><b>越权判据是"身份来源"，不是"路径前缀"</b>：只有签名认证过的开放面请求才带 appId；
 * 内部面（{@code /api/**}）没有上下文，代表平台自己——所以"管理员例外"是刻意设计，
 * 本类最后一条用例专门钉住它（内部面能看到全部）。
 *
 * <p>三类资源各有一条反例：**模板读**、**模板发布**、**领券**；再加**券包隔离**与**内部面不受限**。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class OpenApiOwnershipIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String APP_A = "t-owner-a";
    private static final String APP_B = "t-owner-b";
    private static final long USER_ID = 99_001L;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    @Autowired
    private ObjectMapper objectMapper;

    private static OpenApiTestClient client;
    private static String secretA;
    private static String secretB;
    private static Long templateIdOfA;
    private static Long couponIdOfA;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() throws Exception {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        client = new OpenApiTestClient(port);
        secretA = appService.create(new CreateAppRequest(APP_A, "甲应用", null, null, null)).secret();
        secretB = appService.create(new CreateAppRequest(APP_B, "乙应用", null, null, null)).secret();

        // 甲：建券 → 发布 → 给用户领一张（全部走开放面，带 clientToken）
        templateIdOfA = readLong(client.post("/open/v1/templates", """
                {"name":"甲应用的券","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":100,"perUserLimit":5,
                 "validType":2,"validDays":7}""", APP_A, secretA, "own-create-1").body(), "/data/id");
        client.post("/open/v1/templates/" + templateIdOfA + "/publish", "", APP_A, secretA, "own-publish-1");
        String received = client.post("/open/v1/coupons/receive",
                receiveBody("own-rcv-1", USER_ID), APP_A, secretA, "own-rcv-token-1").body();
        couponIdOfA = readLong(received, "/data/couponId");
    }

    // ------------------------------------------------------------------
    // 反例：乙应用碰甲应用的资源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("越权①：乙应用读甲应用的模板 → 403 + 6011")
    void otherAppTemplate_notVisible() throws Exception {
        OpenApiTestClient.Response r = client.get("/open/v1/templates/" + templateIdOfA, null, APP_B, secretB);

        assertThat(r.status()).as(r.body()).isEqualTo(403);
        assertThat(r.body()).contains("6011");
    }

    @Test
    @DisplayName("越权②：乙应用发布甲应用的模板 → 403 + 6011")
    void otherAppTemplate_notPublishable() throws Exception {
        // 用乙的密钥给另一个模板发发布请求（模板属于甲）
        OpenApiTestClient.Response r = client.post(
                "/open/v1/templates/" + templateIdOfA + "/publish", "", APP_B, secretB, "own-pub-b-1");

        assertThat(r.status()).as(r.body()).isEqualTo(403);
        assertThat(r.body()).contains("6011");
    }

    @Test
    @DisplayName("越权③：乙应用用甲应用的模板领券 → 403 + 6011（领券也要拦）")
    void receiveOtherAppTemplate_rejected() throws Exception {
        OpenApiTestClient.Response r = client.post("/open/v1/coupons/receive",
                receiveBody("own-rcv-b-1", USER_ID + 1), APP_B, secretB, "own-rcv-token-b-1");

        assertThat(r.status()).as(r.body()).isEqualTo(403);
        assertThat(r.body()).contains("6011");
        assertThat(r.body()).as("不泄漏'这个模板是否在领取期'——越权者不该看到别人的运营状态")
                .doesNotContain("2002");
    }

    @Test
    @DisplayName("越权④：券包按应用隔离——同一个 userId 在乙应用下看不到甲应用的券")
    void walletIsolatedBetweenApps() throws Exception {
        OpenApiTestClient.Response asA = client.get("/open/v1/coupons", "userId=" + USER_ID, APP_A, secretA);
        OpenApiTestClient.Response asB = client.get("/open/v1/coupons", "userId=" + USER_ID, APP_B, secretB);

        assertThat(asA.status()).isEqualTo(200);
        assertThat(couponCount(asA.body())).as("甲看得到自己的券").isEqualTo(1);
        assertThat(asB.status()).isEqualTo(200);
        assertThat(couponCount(asB.body()))
                .as("乙看到的是空钱包——(appId, userId) 才是'一个逻辑用户'").isZero();
    }

    @Test
    @DisplayName("越权⑤：乙应用锁定甲应用的券 → 403 + 6011（用券三段也要拦）")
    void lockOtherAppCoupon_rejected() throws Exception {
        OpenApiTestClient.Response r = client.post("/open/v1/coupons/lock",
                "{\"orderNo\":\"own-order-b-1\",\"userId\":" + USER_ID + ",\"couponId\":" + couponIdOfA + "}",
                APP_B, secretB, "own-lock-b-1");

        assertThat(r.status()).as(r.body()).isEqualTo(403);
        assertThat(r.body()).contains("6011");
    }

    // ------------------------------------------------------------------
    // 正例与例外
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正例：甲应用读自己的模板 → 200（防线不是把门焊死）")
    void ownTemplate_accessible() throws Exception {
        OpenApiTestClient.Response r = client.get("/open/v1/templates/" + templateIdOfA, null, APP_A, secretA);

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("\"code\":0");
    }

    @Test
    @DisplayName("例外：内部面（/api，无签名）不受归属限制——管理员视角，属刻意设计")
    void internalFace_notRestricted() throws Exception {
        // 显式带 Accept：不带时内容协商可能挑中 XML 转换器（实测拿到 <Result>...</Result>，
        // 解析当然失败——**这是用例自己的坑，不是实现的**）
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/coupons?userId=" + USER_ID))
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(10))
                .GET().build();
        String body = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();

        assertThat(couponCount(body)).as("内部面能看到全部应用的券（运营/对账视角）").isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private String receiveBody(String requestId, long userId) {
        return "{\"requestId\":\"" + requestId + "\",\"userId\":" + userId
                + ",\"templateId\":" + templateIdOfA + "}";
    }

    private long couponCount(String json) {
        try {
            JsonNode data = objectMapper.readTree(json).path("data");
            // 内部面返回 MyCouponList：{"data":{"coupons":[...]}}
            return data.path("coupons").size();
        } catch (Exception e) {
            throw new AssertionError("响应无法解析: " + json, e);
        }
    }

    private long readLong(String json, String pointer) {
        try {
            JsonNode node = objectMapper.readTree(json).at(pointer);
            assertThat(node.isMissingNode() || node.isNull())
                    .as("响应里找不到 " + pointer + "：" + json).isFalse();
            return node.asLong();
        } catch (Exception e) {
            throw new AssertionError("响应无法解析: " + json, e);
        }
    }
}
