package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * S2 第 8 步：租户级熔断（RQ-6）——<b>红绿灯</b>。
 *
 * <p>红：本类先于"隔离"实现写就——{@code forTenant} 暂时返回依赖级熔断器，
 * 于是"打开甲的熔断"会**连带影响乙**，而本类最核心的那条断言正是"乙不受影响"。
 *
 * <p><b>两层熔断的分工</b>：依赖级是**总闸**（保护依赖本身，所有租户一起快速失败）；
 * 租户级是**分闸**（识别"是谁在制造异常"并把它单独拦下）。顺序是先分闸后总闸——
 * 先看是不是某个租户的问题，再看是不是依赖整体的问题。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.circuit-breaker.min-calls=5",
                "coupon.circuit-breaker.window-size=10"
        })
class TenantCircuitBreakerIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService openAppService;

    @Autowired
    private TemplateAppService templateAppService;

    @Autowired
    private TenantConfigService tenantConfigService;

    @Autowired
    private DependencyCircuitBreakers breakers;

    private OpenApiTestClient client;

    // 每个用例自己的两个租户与两张券——**不共享**，起点干净由用例自己保证
    private String appA;
    private String secretA;
    private Long templateA;
    private String appB;
    private String secretB;
    private Long templateB;

    @BeforeEach
    void setUp() {
        client = new OpenApiTestClient(port);
        int n = SEQ.incrementAndGet();
        appA = "t-tcb-a" + n;
        appB = "t-tcb-b" + n;
        secretA = createApp(appA);
        secretB = createApp(appB);
        templateA = createPublishedTemplate("租户熔断券A" + n, appA);
        templateB = createPublishedTemplate("租户熔断券B" + n, appB);
    }

    @AfterEach
    void resetBreakers() {
        breakers.redis().reset();
        breakers.mysql().reset();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("隔离：甲的租户级熔断打开 → 甲被拦下，**乙照常发券**")
    void tenantBreakerOpen_isolatesThatTenant() throws Exception {
        breakers.forTenant("redis", appA).transitionToOpenState();

        OpenApiTestClient.Response asA = receive(appA, secretA, templateA, "iso-a");
        assertThat(asA.body()).as("甲被熔断，应当快速失败而不是等超时").contains("5001");

        OpenApiTestClient.Response asB = receive(appB, secretB, templateB, "iso-b");
        assertThat(asB.body())
                .as("乙不受甲的熔断影响——这正是'分闸'的意义")
                .contains("\"code\":0");
    }

    @Test
    @DisplayName("对照：依赖级熔断打开 → 所有租户一起快速失败（总闸保护的是依赖本身）")
    void dependencyBreakerOpen_affectsAllTenants() throws Exception {
        breakers.redis().transitionToOpenState();

        assertThat(receive(appA, secretA, templateA, "all-a").body()).contains("5001");
        assertThat(receive(appB, secretB, templateB, "all-b").body()).contains("5001");
    }

    @Test
    @DisplayName("租户级的阈值来自租户配置（不是写死的）")
    void tenantBreakerConfig_comesFromTenantConfig() {
        String appC = "t-tcb-c" + SEQ.incrementAndGet();
        createApp(appC);
        tenantConfigService.put(appC, java.util.Map.of("circuit_breaker.failure_rate", "80"), "test");

        var breaker = breakers.forTenant("redis", appC);

        assertThat(breaker.getCircuitBreakerConfig().getFailureRateThreshold())
                .as("配了 80 就该用 80，而不是全局默认的 50")
                .isEqualTo(80.0f);
    }

    // ------------------------------------------------------------------

    private OpenApiTestClient.Response receive(String appId, String secret, Long templateId,
                                               String token) throws Exception {
        return client.post("/open/v1/coupons/receive",
                "{\"requestId\":\"" + token + "\",\"userId\":70001,\"templateId\":" + templateId + "}",
                appId, secret, token + "-token");
    }

    private String createApp(String appId) {
        return openAppService.create(new CreateAppRequest(appId, "租户熔断验证", null, null, null)).secret();
    }

    /**
     * 以**某个租户的身份**建一张已发布的券。
     *
     * <p>必须显式设置请求上下文：模板的归属来自 {@code AppOwnership.ownerForNewResource()}，
     * 没有上下文时它会归给内置应用——于是刚才那张券"不属于"任何一个测试租户，
     * 请求会撞越权检查（6011）。（第一版就是这么错的：响应是"无权访问该资源"而不是预期的 5001。）
     */
    private Long createPublishedTemplate(String name, String ownerAppId) {
        com.newcoupon.common.context.RequestContext.set(new com.newcoupon.common.context.RequestContext(
                ownerAppId, "test-trace", Long.MAX_VALUE, false));
        try {
            // 参数顺序：name, activityId, couponType, faceValue, discountCap, threshold,
            //          totalCount, perUserLimit, validType, validStart, validEnd, validDays,
            //          dailyLimit, perUserDailyLimit
            var created = templateAppService.create(new TemplateCreateRequest(
                    name, 1001L, 1, 1000L, null, 10000L, 100L, 5, 2, null, null, 7, 0L, 0));
            templateAppService.publish(created.id());
            return created.id();
        } finally {
            com.newcoupon.common.context.RequestContext.clear();
        }
    }
}
