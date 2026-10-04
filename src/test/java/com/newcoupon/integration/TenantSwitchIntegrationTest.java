package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.TenantConfigService;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 租户级开关的**端到端**行为（S2 第 10 步）——<b>红绿灯</b>。
 *
 * <p><b>红</b>：H1 会失败——开关拦截器还没把 {@code appId} 传下去，
 * 于是"给某个租户关掉领券"配置写进去了、缓存也读得到，<b>但请求照常进业务</b>。
 * 这正是本项目反复强调的"实现了但没人守"最典型的样子：
 * 服务层写好了三层取值、用例也绿了，**最后一公里的接线断了**。
 *
 * <p><b>为什么必须走 HTTP 而不能只测 Service</b>：{@code isOn(key, appId)} 的正确性
 * 已经由 {@code SwitchServiceTest} 的七条分支覆盖了；这里要证明的是
 * "**拦截器确实把请求上下文里的 appId 递给了它**"——那是 Service 层测不到的，
 * 也是接入租户层最容易漏的一步。
 *
 * <p>另外钉住一条**有意的不对称**：内部面（{@code /api/**}）没有接入方身份，
 * 只用全站层——租户开关对它不生效。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class TenantSwitchIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /** 两个独有租户：一个被关、一个不受影响——**对照**才能证明"只拦住了该拦的" */
    private static final String APP_A = "t-switch-a";
    private static final String APP_B = "t-switch-b";
    private static final long USER_ID = 97_001L;
    private static final String OPERATOR = "test-tenant-switch@local";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    @Autowired
    private TenantConfigService configService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate rest;

    private static OpenApiTestClient client;
    private static String secretA;
    private static String secretB;
    private static Long templateIdA;

    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        client = new OpenApiTestClient(port);
        secretA = appService.create(new CreateAppRequest(APP_A, "开关甲", null, null, null)).secret();
        secretB = appService.create(new CreateAppRequest(APP_B, "开关乙", null, null, null)).secret();
        // 甲建一张券并发布：H1 里乙的请求需要走到业务层，得有真实资源
        try {
            templateIdA = readLong(client.post("/open/v1/templates", """
                    {"name":"开关验证券","activityId":1001,"couponType":1,"faceValue":1000,
                     "threshold":10000,"totalCount":100,"perUserLimit":5,
                     "validType":2,"validDays":7}""", APP_A, secretA, "sw-create-1").body(), "/data/id");
            client.post("/open/v1/templates/" + templateIdA + "/publish", "",
                    APP_A, secretA, "sw-publish-1");
        } catch (Exception e) {
            throw new IllegalStateException("准备演练数据失败", e);
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("H1 租户级开关真的拦得住：甲配了 receive=false → 甲领券 5001，乙不受影响")
    void h1_tenantSwitchBlocksOnlyThatTenant() throws Exception {
        configService.put(APP_A, Map.of("switch.receive.enabled", "false"), OPERATOR);

        OpenApiTestClient.Response rA = client.post("/open/v1/coupons/receive",
                receiveBody("sw-a-1"), APP_A, secretA, "sw-a-tok-1");
        assertThat(rA.body())
                .as("甲关了领券入口 → 5001 可重试。若这里是业务码，说明拦截器没把 appId 传下去，"
                        + "租户配置形同虚设")
                .contains("5001");
        assertThat(rA.body()).as("降级信息要说清是哪个开关").contains("领券入口总开关");

        OpenApiTestClient.Response rB = client.post("/open/v1/coupons/receive",
                receiveBody("sw-b-1"), APP_B, secretB, "sw-b-tok-1");
        assertThat(rB.body())
                .as("乙没配这个开关 → 照常走业务（券是甲的，这里应当是越权码 6011，"
                        + "关键是**不能**是 5001）")
                .doesNotContain("5001");
    }

    @Test
    @DisplayName("H2 内部面不受租户开关影响：没有接入方身份，只用全站层（有意的语义不对称）")
    void h2_internalApiIgnoresTenantSwitch() {
        configService.put(APP_A, Map.of("switch.receive.enabled", "false"), OPERATOR);

        // 内部面 /api/**：RequestContext 是 EMPTY，appId 为 null
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var r = rest.postForEntity("/api/coupons/receive",
                new HttpEntity<>("{\"requestId\":\"sw-internal-1\",\"userId\":1,\"templateId\":999999}",
                        headers), String.class);

        assertThat(r.getBody())
                .as("内部面若被租户级开关挡住，说明拦截器把 appId 用错了来源")
                .doesNotContain("5001");
    }

    @Test
    @DisplayName("H3 读取接口给出取值来源：租户配过 → source=TENANT，没配 → GLOBAL/DEFAULT")
    void h3_viewReportsSource() throws Exception {
        configService.put(APP_A, Map.of("switch.receive.enabled", "false"), OPERATOR);

        JsonNode switches = listSwitches(APP_A);
        JsonNode receive = find(switches, "receive.enabled");
        assertThat(receive.path("source").asText())
                .as("光看 on=false 判断不出是谁关的——来源字段就是为排障加的")
                .isEqualTo("TENANT");
        assertThat(receive.path("on").asBoolean()).isFalse();

        JsonNode other = find(switches, "outbox.dispatch.enabled");
        assertThat(other.path("source").asText())
                .as("甲没配过这一项 → 来源应回落到全站层或默认").isIn("GLOBAL", "DEFAULT");
    }

    // ------------------------------------------------------------------

    private JsonNode listSwitches(String appId) throws Exception {
        var r = rest.getForEntity("/api/switches?appId=" + appId, String.class);
        return objectMapper.readTree(r.getBody()).path("data");
    }

    private JsonNode find(JsonNode array, String key) {
        for (JsonNode node : array) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("开关清单里没有 " + key + "：" + array);
    }

    private static String receiveBody(String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"userId\":" + USER_ID
                + ",\"templateId\":" + templateIdA + "}";
    }

    private static Long readLong(String json, String pointer) throws Exception {
        return new ObjectMapper().readTree(json).at(pointer).asLong();
    }
}
