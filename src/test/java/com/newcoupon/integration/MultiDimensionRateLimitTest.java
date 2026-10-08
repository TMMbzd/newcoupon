package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * S2 第 6 步：多维度限流（RQ-4）——<b>红绿灯</b>。
 *
 * <p>红：本类先于实现写就——把 IP / 接口两个维度配好，过滤器也不会看它们，请求照样通过。
 *
 * <p><b>两类维度的区别（本步的要点）</b>：
 * appId 维度是**租户级**的——"这个客户能用多少"是商业约定；
 * IP 与接口维度是**平台级**的——"一个来源/一个接口最多打多少"是自我保护，
 * 与具体客户无关。所以前者放租户配置、后者放全站配置。
 *
 * <p><b>链路维度不做</b>：单体应用没有"下游调用链"这个概念，留待微服务拆分时。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.ratelimit.ip.enabled=true",
                "coupon.ratelimit.ip.qps=2",
                "coupon.ratelimit.resource.enabled=true",
                "coupon.ratelimit.resource.qps=2"
        })
class MultiDimensionRateLimitTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String APP_ID = "t-multi-dim";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    /**
     * 规则是 JVM 级的：同一 JVM 里十几个测试上下文会各自按自己的配置周期重推规则。
     * 所以断言前**自己推一次**（走生产同一条路径），否则测的可能是别人的配置。
     */
    @Autowired
    private com.newcoupon.infrastructure.ratelimit.TenantFlowRuleDataSource dataSource;

    private static OpenApiTestClient client;
    private static String secret;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        if (PROVISIONED.compareAndSet(false, true)) {
            client = new OpenApiTestClient(port);
            // 租户级配额给足，确保被拒时不是它触发的
            secret = appService.create(
                    new CreateAppRequest(APP_ID, "多维限流验证", 1000, 0, null)).secret();
        }
    }

    @Test
    @DisplayName("IP 维度：同一个来源连打超过阈值 → 429，且错误信息指明是哪个维度")
    void ipDimension_limits() throws Exception {
        String ip = "10.1.1." + (System.nanoTime() % 250);

        OpenApiTestClient.Response last = null;
        for (int i = 0; i < 5; i++) {
            last = client.get("/open/v1/coupons", "userId=1", APP_ID, secret, "X-Forwarded-For", ip);
            if (last.status() == 429) {
                break;
            }
        }

        assertThat(last.status()).as(last.body()).isEqualTo(429);
        assertThat(last.body()).contains("6006");
        assertThat(last.body()).as("错误信息要说清是哪一维度被限，否则排查时只能猜")
                .contains("IP");
    }

    @Test
    @DisplayName("IP 维度按来源隔离：换一个来源 IP 就不受另一个 IP 的计数影响")
    void ipDimension_isPerIp() throws Exception {
        String heavyUser = "10.2.2." + (System.nanoTime() % 250);
        String other = "10.3.3." + (System.nanoTime() % 250);

        // 让 heavyUser 打满
        for (int i = 0; i < 5; i++) {
            client.get("/open/v1/coupons", "userId=2", APP_ID, secret, "X-Forwarded-For", heavyUser);
        }

        OpenApiTestClient.Response freshIp = client.get("/open/v1/coupons", "userId=2", APP_ID, secret,
                "X-Forwarded-For", other + "-fresh");

        // 新 IP 的 IP 维度计数是干净的；但**接口维度**是全局累计的，可能仍然拦它——
        // 所以这里断言的是"没有被 IP 维度拦"，而不是"一定成功"
        if (freshIp.status() == 429) {
            assertThat(freshIp.body())
                    .as("即使被拦，也该是接口维度而不是 IP 维度（新 IP 的 IP 计数是空的）")
                    .doesNotContain("IP 维度");
        }
    }

    @Test
    @DisplayName("接口维度：同一个接口被并发打 → 429（与是哪个 IP、哪个租户无关）")
    void resourceDimension_limits() throws Exception {
        dataSource.refreshNow();
        // **第 8 步改了两处**：
        //  ① 判定搬到了 Sentinel 的规则上（原来是过滤器里直接调 Bucket4j，为了给预热/排队腾地方）；
        //  ② 判据从"连打 6 次"改成**并发 8 发**——顺序连打在一秒多一发的节奏下，
        //     对一条 2/秒的规则**永远不会超限**（那个坑本项目踩过四次了：判据里藏着"请求够快"）。
        // 每次请求换一个来源 IP：让 IP 维度永远不触发，于是被拦下就只可能是接口维度。
        int n = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<OpenApiTestClient.Response>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String ip = "10.9.9." + i + "." + (System.nanoTime() % 250);
                futures.add(pool.submit(() -> {
                    start.await();
                    return client.get("/open/v1/coupons", "userId=3", APP_ID, secret,
                            "X-Forwarded-For", ip);
                }));
            }
            start.countDown();
            List<OpenApiTestClient.Response> all = new java.util.ArrayList<>();
            for (var f : futures) {
                all.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }

            var blocked = all.stream().filter(r -> r.status() == 429).findFirst();
            assertThat(blocked)
                    .as("上限 2/秒，8 发并发里必须至少有一个被接口维度拒掉。状态分布="
                            + all.stream().map(OpenApiTestClient.Response::status).sorted().toList())
                    .isPresent();
            assertThat(blocked.get().body())
                    .as("要说清是接口维度被限。实际=" + blocked.get().body())
                    .contains("接口");
        } finally {
            pool.shutdownNow();
        }
    }
}
