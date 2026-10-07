package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 第 6 步：**IP 维度精确限流**（自建 {@code IpExactSlot}）——迁移后的验收（2026-10-08）。
 *
 * <p>验收标准原文（设计文档 §6 步骤 6）："连打超限被拒；不同 IP 互不影响"。
 *
 * <p><b>为什么用并发而不是"连打 N 次"</b>：令牌桶的补充是连续的，而这里
 * <b>一发请求要一秒多</b>——顺序连打 N 次，每次之间补充的令牌早就够下一次用了，
 * 于是"连打"永远打不出超限（这是第 4 次踩同一个坑：<b>判据里藏着"请求够快"这个前提</b>）。
 * 并发才让"同一个窗口内的多个请求"成为**可控的量**。
 *
 * <p><b>为什么断言"至少一个被拒"而不是"恰好 qps 个通过"</b>：桶是**并发消费**的，
 * 测试机一忙，请求到达的时刻就会散开一点，补充进来的令牌数不再是 0——
 * 精确个数会变成不可控的量。而"**上限必须真的生效**"（至少一个被拒、且不是全拒）
 * 才是这一步要守住的东西。要精确验证速率，用 {@code RedisIpExactCheckerTest} 里的记账器，
 * 那是纯内存、毫秒级、完全确定的。
 */
// 必须显式开启 observability：@SpringBootTest 默认禁掉指标导出（/actuator/prometheus 会 404），
// 而本类要断言"被拒在 Prometheus 里可见"。
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168",
                "coupon.ratelimit.ip.enabled=true",
                "coupon.ratelimit.ip.qps=2",
                "coupon.ratelimit.resource.enabled=false",   // 只验 IP 维度，别让接口维度插手
                "coupon.risk.ip-rate.enabled=false"          // 别让风控的 IP 频控抢先拒掉
        })
class SentinelIpDimensionTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String RESOURCE = "/open/v1/coupons";

    /**
     * **每类独有的 appId**（共享容器之后的硬纪律）：写成常量会在**第二次运行**时
     * 撞"appId 已存在"——容器不再按类销毁了。
     */
    private static final String APP_ID = "t-ip-dim-" + (System.nanoTime() % 1_000_000);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate rest;

    private static OpenApiTestClient client;
    private static String secret;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        // 守卫的写法与其它用例类一致：**抢到的那一次才建，其余直接返回**。
        // （第一版我写反了——`if (!CAS) { 建 }` 的效果是"第一次不建、之后每次都建"，
        //   症状极具迷惑性：第一条用例里 client 是 null（NPE），后面每条都撞
        //   "appId 已存在"。静态守卫这类东西，写反了不会报错，只会让行为与意图相反。）
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        client = new OpenApiTestClient(port);
        // 租户级配额给足（1000 QPS / 日配额不限）：被拒时一定是 IP 维度而不是它
        secret = appService.create(new CreateAppRequest(APP_ID, "IP维度验证", 1000, 0, null)).secret();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一个来源并发打 → 超限被拒：429 + 业务码 + Retry-After + 说明是 IP 维度")
    void sameIp_isLimited() throws Exception {
        String ip = uniqueIp("10.71");

        List<OpenApiTestClient.Response> responses = fireConcurrently(8, ip);

        long rejected = responses.stream().filter(r -> r.status() == 429).count();
        long passed = responses.size() - rejected;

        assertThat(rejected)
                .as("上限 2/秒，8 发并发里必须至少有一个被拒——否则 IP 维度根本没生效。状态分布="
                        + statuses(responses))
                .isGreaterThan(0);
        assertThat(passed)
                .as("也不该全拒（桶的初始令牌数 = 容量）。状态分布=" + statuses(responses))
                .isGreaterThan(0);

        OpenApiTestClient.Response blocked = responses.stream()
                .filter(r -> r.status() == 429).findFirst().orElseThrow();
        assertThat(blocked.body())
                .as("业务码要与迁移前一致（6006=请求速率超限）").contains("6006");
        assertThat(blocked.body())
                .as("要说清是哪一维度被限，否则接入方与运维只能猜。实际=" + blocked.body())
                .contains("IP");
        assertThat(blocked.header("retry-after"))
                .as("429 必须告诉对方多久后重试——否则只会死循环重试，把限流变成自杀")
                .isNotNull();
    }

    @Test
    @DisplayName("按来源隔离：另一个 IP 完全不占这个 IP 的额度")
    void differentIp_isIndependent() throws Exception {
        String heavy = uniqueIp("10.72");
        String fresh = uniqueIp("10.73");

        fireConcurrently(8, heavy);   // 把 heavy 打满（结果不重要）

        var other = client.get(RESOURCE, "userId=1", APP_ID, secret, "X-Forwarded-For", fresh);

        assertThat(other.status())
                .as("IP 维度的计数是按来源分的：新来源的桶是满的。响应=" + other.body())
                .isNotEqualTo(429);
    }

    @Test
    @DisplayName("被拒在 Prometheus 里可见：按维度打点，且**绝不把 IP 值当标签**")
    void rejectionIsVisibleWithoutCardinalityExplosion() throws Exception {
        String ip = uniqueIp("10.74");
        fireConcurrently(8, ip);      // 自己造出这次拒绝，断言才自包含

        var scrape = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode().value()).isEqualTo(200);

        assertThat(scrape.getBody())
                .as("仪表盘要能回答「现在有多少请求被限流挡着」")
                .contains("coupon_ratelimit_rejected_total");
        assertThat(scrape.getBody())
                .as("维度标签要能区分是谁被限了（ip / resource / tenant_qps / tenant_daily / risk_ip）")
                .contains("dimension=\"ip\"");
        assertThat(scrape.getBody())
                .as("**IP 值绝不能成为标签**——它是请求方可控的无界维度，"
                        + "随便换来源就能让时间序列无限增长（cardinality DoS）。实际 IP=" + ip)
                .doesNotContain(ip);
    }

    // ------------------------------------------------------------------

    /** 同一秒内尽量一起到达：**并发**才让"落在同一个窗口里"成为可控的量 */
    private List<OpenApiTestClient.Response> fireConcurrently(int n, String ip) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<OpenApiTestClient.Response>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<OpenApiTestClient.Response> task = () -> {
                    start.await();
                    return client.get(RESOURCE, "userId=1", APP_ID, secret, "X-Forwarded-For", ip);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();   // 一起放出去
            List<OpenApiTestClient.Response> out = new ArrayList<>();
            for (Future<OpenApiTestClient.Response> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 每个用例独占一个来源 IP：共享容器/共享 Redis 下，"数据自己建且别人建不出来"的同一纪律 */
    private static String uniqueIp(String prefix) {
        return prefix + "." + (System.nanoTime() % 250) + "." + (System.nanoTime() % 250);
    }

    private static List<Integer> statuses(List<OpenApiTestClient.Response> responses) {
        return responses.stream().map(OpenApiTestClient.Response::status).sorted().toList();
    }
}
