package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.common.crypto.OpenApiSignature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * S1 第 7 步：接入方配额（RQ-6）——<b>红绿灯</b>。
 *
 * <p>红：本类先于 {@code OpenApiQuotaFilter} 写就——此时没有配额检查，预填的"超额计数"根本没人看，
 * 请求照样 200。这条红灯证明用例真的在测"配额是否生效"。
 *
 * <p><b>为什么用"预填计数器"而不是"连打 N 个请求"</b>：后者依赖"N 个请求落在同一秒内"，
 * 是典型的 flaky 用例（首次请求要回源查库、缓存装载，一不小心就跨秒了）。
 * 预填计数器 + 另有一条"请求确实会让计数 +1"的用例，合起来覆盖了同一段逻辑，
 * 且**与机器快慢无关**。测试要的是确定性，不是"看起来更真实"。
 *
 * <p><b>为什么这个类用 JDK 的 {@link HttpClient} 而不是 {@code TestRestTemplate}</b>：
 * 实测（2026-10-02）发现 {@code TestRestTemplate} 底层的 {@code HttpURLConnection}
 * 在收到 <b>429 + {@code Retry-After}</b> 时会出现两个问题：① 读响应阻塞（无读超时则一直等，
 * 用例因此卡满 90 秒超时）；② 随后自动重试，且重试**携带同一个 nonce**，于是第二次被
 * nonce 防重放挡下（6005），用例拿到的是 401 而不是 429。
 * 同一时刻用 JDK {@code HttpClient} 发同样的请求，<b>458ms 就拿到了正确的 429</b>——
 * 所以这是**测试客户端实现的行为，不是服务端缺陷**。
 * 服务端该有的行为（429 必带 {@code Retry-After}）保持不变。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)   // 防再次卡死：卡住时能明确指到方法
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class OpenApiQuotaIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String LIMITED_APP = "t-quota";
    private static final String UNLIMITED_APP = "t-quota-free";

    /** 与实现约定的键格式（用例先写，实现来对齐——这就是"测试定义契约"） */
    private static final String QPS_KEY_PREFIX = "c:open:qps:";
    private static final String DAILY_KEY_PREFIX = "c:open:quota:";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    @Autowired
    private StringRedisTemplate redis;

    private static String limitedSecret;
    private static String unlimitedSecret;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        limitedSecret = appService.create(
                new CreateAppRequest(LIMITED_APP, "配额受限接入方", 2, 3, null)).secret();
        unlimitedSecret = appService.create(
                new CreateAppRequest(UNLIMITED_APP, "不限量接入方", 0, 0, null)).secret();
    }

    @Test
    @DisplayName("QPS 超限 → 429 + 6006 + Retry-After")
    void qpsExceeded_rejected() throws Exception {
        prefillQps(LIMITED_APP, 99);

        Call result = call(LIMITED_APP, limitedSecret);

        assertThat(result.status()).as(result.body()).isEqualTo(429);
        assertThat(result.body()).contains("6006");
        assertThat(result.retryAfter())
                .as("429 必须告诉调用方多久后重试——否则对方只能瞎猜或死循环重试")
                .isNotNull();
    }

    @Test
    @DisplayName("日配额超限 → 429 + 6007")
    void dailyQuotaExceeded_rejected() throws Exception {
        prefillDaily(LIMITED_APP, 99);

        Call result = call(LIMITED_APP, limitedSecret);

        assertThat(result.status()).as(result.body()).isEqualTo(429);
        assertThat(result.body()).contains("6007");
        assertThat(result.retryAfter()).isNotNull();
    }

    @Test
    @DisplayName("放行的请求确实会让计数 +1（证明配额是真的在数，不是只在超限时才看一眼）")
    void allowedRequest_incrementsCounters() throws Exception {
        String qpsKey = qpsKey(LIMITED_APP);
        redis.delete(qpsKey);
        redis.delete(dailyKey(LIMITED_APP));

        Call result = call(LIMITED_APP, limitedSecret);
        assertThat(result.status()).as(result.body()).isEqualTo(200);

        assertThat(redis.opsForValue().get(qpsKey)).as("QPS 计数").isEqualTo("1");
        assertThat(redis.opsForValue().get(dailyKey(LIMITED_APP))).as("日配额计数").isEqualTo("1");
    }

    @Test
    @DisplayName("配额为 0 = 不限：预填再大的计数也不拦（内部应用用）")
    void unlimitedApp_neverThrottled() throws Exception {
        prefillQps(UNLIMITED_APP, 999_999);
        prefillDaily(UNLIMITED_APP, 999_999);

        Call result = call(UNLIMITED_APP, unlimitedSecret);

        assertThat(result.status()).as(result.body())
                .as("0=不做这个判断，而不是'给个很大的数'").isEqualTo(200);
    }

    // ------------------------------------------------------------------

    private record Call(int status, String body, String retryAfter) {
    }

    private void prefillQps(String appId, long value) {
        redis.opsForValue().set(qpsKey(appId), String.valueOf(value), Duration.ofSeconds(5));
    }

    private void prefillDaily(String appId, long value) {
        redis.opsForValue().set(dailyKey(appId), String.valueOf(value), Duration.ofSeconds(60));
    }

    private static String qpsKey(String appId) {
        return QPS_KEY_PREFIX + appId + ":" + (System.currentTimeMillis() / 1000);
    }

    private static String dailyKey(String appId) {
        return DAILY_KEY_PREFIX + appId + ":" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private Call call(String appId, String secret) throws Exception {
        String path = "/open/v1/coupons";
        String query = "userId=88001";
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String canonical = OpenApiSignature.canonicalString("GET", path,
                OpenApiSignature.canonicalQuery(query), OpenApiSignature.bodySha256(new byte[0]),
                appId, Long.parseLong(ts), nonce);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + "?" + query))
                .header("Authorization", OpenApiSignature.SCHEME + " Credential=" + appId
                        + ", Timestamp=" + ts
                        + ", Nonce=" + nonce
                        + ", Signature=" + OpenApiSignature.sign(secret, canonical))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Call(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").orElse(null));
    }
}
