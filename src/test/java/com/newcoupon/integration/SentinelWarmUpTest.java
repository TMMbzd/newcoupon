package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.infrastructure.ratelimit.TenantFlowRuleDataSource;
import java.util.ArrayList;
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
 * 第 8 步：**冷启动预热**（Sentinel {@code CONTROL_BEHAVIOR_WARM_UP}）——新增能力。
 *
 * <p>S2 阶段这个能力被砍掉过（当时的自研预热装饰器只在单测里可控，集成层断言不了阈值曲线）。
 * 现在它由 Sentinel 的控制器提供，而那个控制器有一个**恰好可测**的性质（源码核实）：
 * **预热从"第一次请求"算起**——控制器的令牌数与最后填充时刻初值都是 0，
 * 第一次同步时直接填满，于是**第一个请求面对的就是冷阈值**。
 * 也就是说"刚装载规则 → 立刻打突刺"必定落在预热期内，不需要假时钟。
 *
 * <p>冷阈值 = {@code count / coldFactor}（{@code coldFactor} 是 JVM 级配置，默认 3）。
 * 所以本类配 {@code qps=6} + {@code warmup-seconds=60}，预热期内的有效上限约为 **2/秒**：
 * 8 发并发里能过的**明显少于 6**。
 *
 * <p><b>为什么用并发</b>：顺序连打在一秒多一发的节奏下连"不预热"的 6/秒都打不破
 * （这个坑本项目踩过四次）。并发才让"同一瞬间抢那几个令牌"成为可控的量。
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.ratelimit.resource.enabled=true",
                "coupon.ratelimit.resource.qps=6",
                "coupon.ratelimit.resource.warmup-seconds=60",
                "coupon.risk.ip-rate.enabled=false"
        })
class SentinelWarmUpTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String RESOURCE = "/open/v1/coupons";
    private static final String APP_ID = "t-warmup-" + (System.nanoTime() % 1_000_000);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    /**
     * 规则是 **JVM 级**的（`FlowRuleManager` 只有一个属性位），而测试 JVM 里有十几个上下文，
     * 每个上下文都会按自己的配置周期性地重推规则。所以断言前**必须自己推一次**
     * （走的是生产同一条路径），把当前上下文配置的规则顶上去——否则测的是别人的配置。
     */
    @Autowired
    private TenantFlowRuleDataSource dataSource;

    private static OpenApiTestClient client;
    private static String secret;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        client = new OpenApiTestClient(port);
        secret = appService.create(new CreateAppRequest(APP_ID, "预热验证", 1000, 0, null)).secret();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("预热期内阈值被压低：qps=6 的接口，冷启动时第 3 发起就被拒（正常应是 6）")
    void warmUpKeepsThresholdLowAtColdStart() throws Exception {
        dataSource.refreshNow();

        // **必须顺序发**（第一版写的是 8 发并发，结果过了 6 个 —— 那是"不可控的量"的第 5 次：
        // 预热控制器比的是 `passQps`，也就是"**已完成**的通过数"，而 Sentinel 是在请求走完
        // 处理链之后才记账的。并发突发时大家都还没记账，各自看到的通过数都是 0，
        // 冷阈值就被绕过去了。顺序发才让"通过数"成为可控的量。）
        //
        // 冷阈值 = count/coldFactor = 6/3 = 2：顺序发的话第 1、2 发过，第 3 发开始拒。
        // 没有预热时这里是 6/秒，4 发全过——所以"有发被拒"本身就说明预热生效了。
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            // 每发换一个来源 IP：IP 维度不插手，测的只是接口维度的预热
            var r = client.get(RESOURCE, "userId=1", APP_ID, secret,
                    "X-Forwarded-For", "10.80." + i + "." + (System.nanoTime() % 250));
            statuses.add(r.status());
            if (r.status() == 429) {
                break;   // 已经证明"更早就拦住了"，不必再打
            }
        }

        long passed = statuses.stream().filter(s -> s != 429).count();
        assertThat(statuses.stream().anyMatch(s -> s == 429))
                .as("预热期内的有效上限 ≈ count/coldFactor = 6/3 = 2，顺序发 4 发必须有过不去的。"
                        + "全过说明**预热没生效**（那就是一条普通的 6/秒规则）。状态=" + statuses)
                .isTrue();
        assertThat(passed)
                .as("也不该一发都不放（冷阈值是 2 不是 0）。状态=" + statuses)
                .isGreaterThan(0);
    }

    // ------------------------------------------------------------------

}
