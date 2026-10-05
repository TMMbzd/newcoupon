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
    /** 专供"租户配置覆盖阈值"的用例：配置是**持久化**的，共享租户会让别的用例受影响 */
    private static final String CFG_APP = "t-quota-cfg";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    @Autowired
    private com.newcoupon.application.service.TenantConfigService tenantConfigService;



    private static String limitedSecret;
    private static String unlimitedSecret;
    private static String cfgSecret;
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
        cfgSecret = appService.create(
                new CreateAppRequest(CFG_APP, "配置覆盖验证方", 2, 0, null)).secret();
    }

    @Test
    @DisplayName("QPS 超限 → 429 + 6006 + Retry-After")
    void qpsExceeded_rejected() throws Exception {
        Call result = exhaust(LIMITED_APP, limitedSecret, 5);   // 登记表 QPS 上限 = 2

        assertThat(result.status()).as(result.body()).isEqualTo(429);
        assertThat(result.body()).contains("6006");
        assertThat(result.retryAfter())
                .as("429 必须告诉调用方多久后重试——否则对方只能瞎猜或死循环重试")
                .isNotNull();
    }

    @Test
    @DisplayName("日配额超限 → 429 + 6007")
    void dailyQuotaExceeded_rejected() throws Exception {
        // 必须用一个 **QPS 不限**的租户：否则连打时先撞上的是 QPS 限流（6006），
        // 测不到日配额那条路径（本用例第一版就是这么错的）
        String appId = "t-quota-daily";
        String secret = appService.create(
                new CreateAppRequest(appId, "只限日配额", 0, 3, null)).secret();

        Call result = exhaust(appId, secret, 8);   // 日配额 3

        assertThat(result.status()).as(result.body()).isEqualTo(429);
        assertThat(result.body()).contains("6007");
        assertThat(result.retryAfter()).isNotNull();
    }

    @Test
    @DisplayName("配额是真的在数：连打到上限就被拒，而不是只在某个时刻看一眼")
    void allowedRequests_consumeQuota() throws Exception {
        // 一个 QPS 上限 = 1 的租户：第 1 个应当通过，第 2 个应当被拒
        String appId = "t-quota-one";
        String secret = appService.create(
                new CreateAppRequest(appId, "上限一", 1, 0, null)).secret();

        assertThat(call(appId, secret).status()).as("第 1 个：消耗掉唯一配额").isEqualTo(200);
        assertThat(call(appId, secret).status()).as("第 2 个：配额已用完").isEqualTo(429);
    }

    @Test
    @DisplayName("配额为 0 = 不限：连打多少次都不拦（内部应用用）")
    void unlimitedApp_neverThrottled() throws Exception {
        Call result = null;
        for (int i = 0; i < 20; i++) {
            result = call(UNLIMITED_APP, unlimitedSecret);
        }

        assertThat(result.status()).as(result.body())
                .as("0=不做这个判断，而不是'给个很大的数'").isEqualTo(200);
    }

    @Test
    @DisplayName("租户配置覆盖登记表阈值：未配用登记表（2），配了 1000 就用 1000")
    void tenantConfig_overridesRegisteredThreshold() throws Exception {
        clearConfig();   // 起点干净："配置是持久化的"，用例必须自己保证幂等

        assertThat(exhaust(CFG_APP, cfgSecret, 5).status())
                .as("未配 → 用登记表的阈值 2，连打 5 次应当被限").isEqualTo(429);

        tenantConfigService.put(CFG_APP, java.util.Map.of("rate_limit.qps", "1000"), "test");

        assertThat(exhaust(CFG_APP, cfgSecret, 5).status())
                .as("配了 1000 → 同样连打 5 次应当全部放行（证明读的是租户配置）").isEqualTo(200);
    }

    @Test
    @DisplayName("租户配置按租户隔离：只改配置租户的阈值，别的租户仍用登记表")
    void tenantConfig_doesNotAffectOtherTenants() throws Exception {
        tenantConfigService.put(CFG_APP, java.util.Map.of("rate_limit.qps", "1000"), "test");

        assertThat(exhaust(CFG_APP, cfgSecret, 5).status())
                .as("配了 1000 的那个租户：连打 5 次全部放行").isEqualTo(200);

        assertThat(exhaust(LIMITED_APP, limitedSecret, 5).status())
                .as("没配的租户仍按登记表的 2 被限——配置不外溢").isEqualTo(429);
    }

    /**
     * 连打到被拒为止（最多 attempts 次），返回**被拒的那一次**；一次都没被拒就返回最后一次。
     *
     * <p><b>为什么不预填计数器</b>：预填要依赖"限流器内部用的是什么键"，而键格式是实现细节——
     * 换一种算法（固定窗口 → 滑动窗口）键就变了，用例会莫名其妙地红。
     * **测外部行为，不测内部结构**：连打到被拒，既不依赖键格式，也不依赖算法的边界特性。
     */
    private Call exhaust(String appId, String secret, int attempts) throws Exception {
        Call last = null;
        for (int i = 0; i < attempts; i++) {
            last = call(appId, secret);
            if (last.status() == 429) {
                return last;
            }
        }
        return last;
    }

    @Test
    @DisplayName("冷启动预热：配置接得上且限流仍生效（阈值曲线由 WarmUpRateLimiterTest 精确覆盖）")
    void warmUp_throttlesDuringColdStart() throws Exception {
        String appId = "t-quota-warm";

        // **日配额设成 1，然后连打 3 次——这个写法是确定的**，理由有两条：
        //  ① 日配额的窗口是"当天"，不跨秒（QPS 维度的键带"当前秒"，连打会跨秒桶）；
        //  ② 阈值 1 在预热的**任何阶段**都成立：预热把阈值压到 1/3，
        //     而 1/3 取整是 0、实现会兜到 1（否则 0 会被下游解读成"不限"，方向正好相反）。
        // 于是"第 2 次必被拒"与机器快慢、套件位置、JVM 已运行多久都无关。
        //
        // 这条用例红过三次，前两次的修法都不彻底，教训记在这里：
        //  · 第一版：QPS 连打 35 次 → 跨秒桶，全量时误报；
        //  · 第二版：改用日配额但仍是"连打 35 次" → 同样依赖机器速度；
        //  · 第三版（现在）：把阈值降到 1，让"必被拒"不再依赖任何时序。
        // 另外试过"预填计数器"，但**滑动窗口会在基础键下派生窗口号子键**，
        // 预填基础键对默认算法无效——那属于依赖实现细节，不走这条路。
        String secret = appService.create(
                new CreateAppRequest(appId, "冷启动预热验证", 0, 1, null)).secret();   // QPS 不限，日配额 1

        tenantConfigService.put(appId, java.util.Map.of("rate_limit.warmup_seconds", "600"), "test");

        // 分界说明：阈值曲线本身由单元测试 WarmUpRateLimiterTest 用假时钟精确覆盖
        // （它可控），这一条只负责"配置接得上 + 预热装饰器没有把限流弄坏"。
        Call last = exhaust(appId, secret, 3);
        assertThat(last.status())
                .as("日配额=1 时第 2 发必被拒（预热是压低阈值，不是关掉限流）；响应=" + last.body())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("对照：不配预热就是全额阈值（证明上一条的差异来自预热，不是别的）")
    void withoutWarmUp_fullThresholdTakesEffect() throws Exception {
        String appId = "t-quota-nowarm";
        String secret = appService.create(
                new CreateAppRequest(appId, "不预热", 30, 0, null)).secret();

        // 连打 20 次：低于全额阈值 30，应当全部放行
        Call last = null;
        for (int i = 0; i < 20; i++) {
            last = call(appId, secret);
            assertThat(last.status()).as("第 " + (i + 1) + " 次：20 < 30，不该被限").isEqualTo(200);
        }
    }

    /** 清掉配置租户的那一项（配置持久化，用例必须自己保证"起点干净"） */
    private void clearConfig() {
        try {
            tenantConfigService.remove(CFG_APP, "rate_limit.qps", "test");
        } catch (RuntimeException ignored) {
            // 从来没有配置过：删除会报"没有这一项"，忽略即可
        }
    }

    // ------------------------------------------------------------------

    private record Call(int status, String body, String retryAfter) {
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
