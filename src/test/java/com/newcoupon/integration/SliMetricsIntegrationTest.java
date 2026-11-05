package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.infrastructure.metrics.SliMetrics;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * SLI 埋点进真实链路与 Prometheus 抓取面（V6 5A 红绿灯）。
 *
 * <p><b>红</b>：本类先于埋点存在——SliAspect 未挂上时四条链路怎么打，
 * {@code coupon_sli_events_total} 都是 0，三 outcome 断言全红。
 *
 * <p>用例 1 走真库真链路打出三 outcome：成功领取（good）、库存售罄（excluded）、
 * 依赖熔断打开的快速失败（bad——手动把 mysql 熔断器扳到 OPEN，收口后复位，
 * 与 GovernanceMetricsIntegrationTest 同款进程级状态处理）。
 * "excluded 不进可用性分母"的断言在 Prometheus 规则测试侧
 * （ops/prometheus/slo-rules.tests.yml 用例 4 的公式验证），本类只管计数正确。
 *
 * <p><b>为什么 properties 与 GovernanceMetricsIntegrationTest 逐字相同</b>：
 * Spring 按属性组合缓存上下文，逐字对齐才能复用同一上下文（共享容器时代的 WSL 内存纪律）；
 * ip-rate 收紧只影响 HTTP 过滤器，本类直调 AppService，不受影响。
 */
// 必须显式开 observability：@SpringBootTest 默认关指标导出，/actuator/prometheus 会 404
// （坑位说明见 GovernanceMetricsIntegrationTest 类注释）
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.risk.ip-rate.max-per-window=1",
                "coupon.risk.ip-rate.window-seconds=60",
                "coupon.security.actuator.username=actuator",
                "coupon.security.actuator.password=it-actuator-pass"
        })
class SliMetricsIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong USER =
            new java.util.concurrent.atomic.AtomicLong(410_000);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private DependencyCircuitBreakers breakers;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("领券三连发：成功=good / 售罄=excluded / 熔断快速失败=bad，计数与计时各就位")
    void receiveJourneyCountsThreeOutcomes() {
        // 库存=1 的模板：第一发领走唯一库存（good），第二发撞售罄（excluded）
        long templateId = createTemplateWithStockOne();

        double goodBefore = events(SliMetrics.Outcome.GOOD);
        double excludedBefore = events(SliMetrics.Outcome.EXCLUDED);
        double badBefore = events(SliMetrics.Outcome.BAD);
        double timerBefore = receiveTimerCount();

        // ① good：正常领取成功（业务码 0）
        receiveAppService.receive(new ReceiveRequest(
                "sli-good-" + SEQ.incrementAndGet(), USER.incrementAndGet(), templateId));

        // ② excluded：库存 1 已被①领走 → STOCK_OUT(3001)=业务拒绝
        try {
            receiveAppService.receive(new ReceiveRequest(
                    "sli-soldout-" + SEQ.incrementAndGet(), USER.incrementAndGet(), templateId));
            throw new AssertionError("第二发应售罄被拒");
        } catch (BizException e) {
            assertThat(e.getErrorCode().getCode()).isEqualTo(3001);
        }

        // ③ bad：mysql 熔断器扳 OPEN → 领券落库段快速失败 5xxx（模板读缓存不受影响）
        breakers.mysql().transitionToOpenState();
        try {
            receiveAppService.receive(new ReceiveRequest(
                    "sli-bad-" + SEQ.incrementAndGet(), USER.incrementAndGet(), templateId));
            throw new AssertionError("熔断打开时应快速失败");
        } catch (BizException e) {
            assertThat(e.getErrorCode().getCode()).isGreaterThanOrEqualTo(5000);
        } finally {
            breakers.mysql().reset();   // 进程级状态必须复位，别影响同上下文的别的用例
        }

        assertThat(events(SliMetrics.Outcome.GOOD)).as("成功一发 = good +1").isEqualTo(goodBefore + 1);
        assertThat(events(SliMetrics.Outcome.EXCLUDED)).as("售罄一发 = excluded +1").isEqualTo(excludedBefore + 1);
        assertThat(events(SliMetrics.Outcome.BAD)).as("熔断快速失败一发 = bad +1").isEqualTo(badBefore + 1);
        assertThat(receiveTimerCount()).as("三发都停表计时").isEqualTo(timerBefore + 3);
    }

    @Test
    @DisplayName("抓取面暴露：coupon_sli_events_total 与 coupon_deployment_info 在 /actuator/prometheus 可见")
    void sliMetricsAreExposedOnScrapeEndpoint() {
        String text = scrape();

        assertThat(text)
                .as("SLI 事件指标必须出现在抓取文本（Micrometer 命名转换：coupon.sli.events → coupon_sli_events_total）")
                .contains("coupon_sli_events_total");
        assertThat(text)
                .as("journey/outcome 是枚举标签——出现任何越界取值即埋点事故")
                .contains("journey=\"receive\"")
                .contains("outcome=\"good\"");
        assertThat(text)
                .as("单实例口径声明要在抓取面可见（SLO.md §6 的口径锚点）")
                .contains("coupon_deployment_info")
                .contains("topology=\"single\"")
                .contains("ha=\"false\"");
        assertThat(text)
                .as("延迟直方图（SLO 档位桶 0.3s=300ms 档）要能支撑 histogram_quantile")
                .contains("coupon_sli_duration_seconds_bucket");
    }

    // ------------------------------------------------------------------

    private long createTemplateWithStockOne() {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "SLI验证券", 1001L, 1, 1000L, null, 10000L, 1L, 1,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, 0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }

    private double events(SliMetrics.Outcome outcome) {
        return meterRegistry.get("coupon.sli.events")
                .tag("journey", SliMetrics.Journey.RECEIVE.tag())
                .tag("outcome", outcome.tag())
                .counter().count();
    }

    private double receiveTimerCount() {
        return meterRegistry.get("coupon.sli.duration")
                .tag("journey", SliMetrics.Journey.RECEIVE.tag())
                .timer().count();
    }

    /** 抓取端点不在匿名面：带 Basic 凭据（与生产抓取器同款，见管理面认证翻面 runbook） */
    private String scrape() {
        HttpHeaders basic = new HttpHeaders();
        basic.setBasicAuth("actuator", "it-actuator-pass");
        var response = rest.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(basic), String.class);
        assertThat(response.getStatusCode().value()).as("抓取端点必须可用").isEqualTo(200);
        return response.getBody();
    }
}
