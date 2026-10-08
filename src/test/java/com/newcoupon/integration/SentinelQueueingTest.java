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
 * 第 8 步：**匀速排队**（Sentinel {@code CONTROL_BEHAVIOR_RATE_LIMITER}）——新增能力，
 * <b>默认关</b>，这里是显式打开后的形状验证。
 *
 * <p><b>它到底是什么</b>（源码核实）：把超出的请求**睡在调用线程上**，等到下一个令牌的时间再放行；
 * 等超过 {@code maxQueueingTimeMs} 就当场拒。所以本类配 {@code qps=2}（间隔 500ms）+
 * {@code queue-ms=2000}：短时间内的第 2、3 发**应当等一会儿然后通过**，
 * 而不是像"快速失败"那样第二发就被拒。
 *
 * <p><b>为什么这条断言有区分度</b>：不排队时，1 秒的滑动窗口放 2 个——
 * 第 3 发在同一秒内必然被拒。所以"**3 发连续都通过**"就足以证明排队在起作用；
 * 再断言"其中至少一发明显变慢"证明它是**等**过去的，而不是被白放过去的。
 *
 * <p><b>代价（写在配置注释与文档里）</b>：排队占的是 Tomcat 工作线程。
 * 等待线程数的上界 ≈ {@code qps × queueMs / 1000}，所以这个值必须按线程预算来定，
 * 不能照 FlowRule 的默认值 500ms 随手打开。
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.ratelimit.resource.enabled=true",
                "coupon.ratelimit.resource.qps=2",
                "coupon.ratelimit.resource.queue-ms=2000",
                "coupon.risk.ip-rate.enabled=false"
        })
class SentinelQueueingTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String RESOURCE = "/open/v1/coupons";
    private static final String APP_ID = "t-queue-" + (System.nanoTime() % 1_000_000);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    /** 规则是 JVM 级的，断言前自己推一次（否则测的是别的上下文的配置） */
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
        secret = appService.create(new CreateAppRequest(APP_ID, "排队验证", 1000, 0, null)).secret();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("排队：同一秒内第 3 发不是被拒，而是「等一会儿再放行」")
    void overLimitRequestsWaitInsteadOfBeingRejected() throws Exception {
        dataSource.refreshNow();

        List<Long> costs = new ArrayList<>();
        List<OpenApiTestClient.Response> responses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            // 每发换一个来源 IP：IP 维度不插手，测的就是接口维度的排队
            responses.add(client.get(RESOURCE, "userId=1", APP_ID, secret,
                    "X-Forwarded-For", "10.81." + i + "." + (System.nanoTime() % 250)));
            costs.add((System.nanoTime() - t0) / 1_000_000);
        }

        assertThat(responses).allSatisfy(r -> assertThat(r.status())
                .as("排队开着的时候，同一秒内的第 3 发应当**等**到下一个令牌再放行；"
                        + "被拒说明排队没生效（那就是普通的 2/秒快速失败）。响应=" + r.body())
                .isNotEqualTo(429));
        assertThat(costs.stream().mapToLong(Long::longValue).max().orElse(0))
                .as("必须有一发是**等**过去的（间隔 500ms）：各发耗时(ms)=" + costs
                        + "。全都很快说明它没排队——那就不是这个能力")
                .isGreaterThan(300);
    }
}
