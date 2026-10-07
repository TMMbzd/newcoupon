package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 治理指标进 Prometheus（S2 第 11 步，RQ-10）——<b>红绿灯</b>。
 *
 * <p><b>红</b>：本类先于打点写就——此时 {@code /actuator/prometheus} 里既没有限流命中，
 * 也没有熔断状态（只有 2e-⑤ 时期的领券四件套）。修完实现后全绿。
 *
 * <p>断言的是**抓取文本**而不是 `MeterRegistry` 内部对象：
 * 验收标准原文就是"`/actuator/prometheus` 能看到这些指标"，
 * 直接断言最终产物，中间经过 Micrometer 命名转换的那一层也一并被验证了
 * （它会把点号转下划线、给不带 {@code _total} 结尾的 counter 补后缀——这些细节骗过不少人）。
 *
 * <p><b>为什么用 IP 频控维度触发限流</b>：它的窗口是 60 秒（不是"每秒一个新桶"），
 * 所以"连打 3 次必被拒"不依赖"3 次落在同一秒内"——那是不可控的量
 * （第 10 步刚踩过：QPS 的 key 带当前秒，"连打 35 次"横跨两个秒桶就全部放行）。
 */
// **必须显式开启 observability**：@SpringBootTest 默认会应用
// DisableObservabilityContextCustomizer（"测试不需要指标导出"），
// 于是 PrometheusMeterRegistry 根本不会被创建，/actuator/prometheus 变成 404。
// 本类要验的正是"抓取端点能看到指标"，所以必须把它打开。
// （这个坑很隐蔽：应用真跑起来时端点完全正常，只有测试里看不到。）
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                // IP 频控收紧到 1 次/窗口：第 2 发必被拒（窗口 60 秒，不受秒边界影响）
                "coupon.risk.ip-rate.max-per-window=1",
                "coupon.risk.ip-rate.window-seconds=60"
        })
class GovernanceMetricsIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OpenAppService appService;

    @Autowired
    private com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers breakers;

    /** 一个"配额为 0 = 不限"的租户：让请求能穿过配额过滤器，专心测平台层指标 */
    private static String appId;

    @BeforeEach
    void provisionOnce() {
        // **守卫写反过（2026-10-08 修）**：原来是 `if (!CAS) { 建 }` ——
        // 效果是"第一条用例不建、之后每条都建"（这里因为每次都用新 appId，所以只是白白多建几个，
        // 没红；同款写法在 SentinelIpDimensionTest 上就红了：client 为 null + "appId 已存在"）。
        // 正确写法是"抢到的那一次才建，其余直接返回"——见工程手记 §2.5。
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        // 注：appId 目前只在这里赋值、没有任何断言用到它（本类的用例走的是**未验签**请求，
        // 归入 unverified 桶）。保留它是因为"租户用量"这条能力迟早要有验签链路的用例，
        // 那时它就是现成的；真要清理请连同这个字段一起去掉。
        appId = "t-metrics-" + SEQ.incrementAndGet();
        appService.create(new CreateAppRequest(appId, "指标验证", 0, 0, null));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("限流命中进 Prometheus：被拒的请求数按维度可见")
    void rateLimitRejectionsAreExposed() {
        // 打两发：第一发放行，第二发撞上 IP 频控（上限 1）
        postReceive();
        postReceive();

        String text = scrape();

        assertThat(text)
                .as("仪表盘要能回答「现在有多少请求被限流挡着」")
                .contains("coupon_ratelimit_rejected_total");
        assertThat(text)
                .as("维度标签要能区分「谁被限了」——但**不能**带 IP 值本身（攻击者可控的无界维度）")
                .contains("dimension=\"risk_ip\"");
        assertThat(text)
                .as("IP 值绝不能成为标签——那是 cardinality DoS 的入口")
                .doesNotContain("127.0.0.1\"");
    }

    @Test
    @DisplayName("熔断状态进 Prometheus：依赖级熔断器在抓取里可见（懒创建也要预热出来）")
    void breakerStateIsExposed() {
        String text = scrape();

        assertThat(text)
                .as("官方集成产出的指标名保持 resilience4j_ 前缀（不改名——上游大盘示例都按它写）")
                .contains("resilience4j_circuitbreaker_state");
        assertThat(text)
                .as("干净启动时熔断器还没被创建过，不预热就一条都没有——"
                        + "运维会以为「没有熔断器」（第 10 步的只读端点踩过同款）")
                .contains("name=\"redis\"");
        assertThat(text).contains("name=\"mysql\"");
    }

    @Test
    @DisplayName("熔断状态转移计数进 Prometheus：官方集成不提供，得自己数")
    void breakerTransitionsAreCounted() {
        // **必须先真的发生一次转移**：Micrometer 的 Counter 在首次 increment 之前
        // 不产生时间序列（这是对的行为——否则每个可能的标签组合都会有一条恒为 0 的序列）。
        // 第一版用例直接断言"指标存在"，在没有任何转移的测试里必然红。
        breakers.redis().transitionToOpenState();
        breakers.redis().reset();   // 复位：熔断器是进程级状态，别影响别的用例

        String text = scrape();

        assertThat(text)
                .as("RQ-10 明确要求「熔断状态与切换次数」，而官方指标集里没有任何 transition 计数")
                .contains("coupon_circuit_breaker_transitions_total");
    }

    @Test
    @DisplayName("租户用量进 Prometheus：验签通过的调用按 appId 可见，未验签的归入固定桶")
    void tenantUsageIsExposed() {
        // **必须打开放面**：用量打点在 OpenApiAuthFilter 里，只有 /open/** 会经过它；
        // 内部面（/api/**）代表平台自己，不计入"租户用量"。
        // 这里故意不带签名——未验签的调用同样会被记，只是归入固定桶 unverified。
        rest.getForEntity("/open/v1/coupons?userId=1", String.class);

        String text = scrape();

        assertThat(text)
                .as("「租户用量」要能回答「谁在打、打了多少」")
                .contains("coupon_open_api_calls_total");
        assertThat(text)
                .as("appId 标签的基数必须**有界**：未验签的请求归入 unverified，"
                        + "否则任何人用随机 appId 就能把时间序列打爆")
                .contains("app_id=\"unverified\"");
    }

    // ------------------------------------------------------------------

    /** 打一发会触发 IP 频控的请求（RiskControlFilter 只拦这个路径） */
    private void postReceive() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity("/api/coupons/receive",
                new HttpEntity<>("{\"requestId\":\"metrics-" + SEQ.incrementAndGet()
                        + "\",\"userId\":1,\"templateId\":999999}", headers), String.class);
    }

    private String scrape() {
        var response = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode().value()).as("抓取端点必须可用").isEqualTo(200);
        return response.getBody();
    }
}
