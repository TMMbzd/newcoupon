package com.newcoupon.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.metrics.SliMetrics.Journey;
import com.newcoupon.infrastructure.metrics.SliMetrics.Outcome;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SLI 判定表单测（V6 5A）——判定表的第三份拷贝（文档=docs/operations/SLO.md §3.2、
 * 代码=SliMetrics.classify、本类=码表）。
 *
 * <p><b>码表驱动</b>：期望值全部手写在 {@link #SPEC} 里（不是用规则反推），
 * 并断言码表覆盖 {@link ErrorCode} 全部枚举值——新增错误码若不更新 SLO 口径，
 * 这里先红（complete­ness 断言），落错桶也在逐码断言里红。
 */
class SliMetricsTest {

    private SimpleMeterRegistry registry;
    private SliMetrics sli;

    /**
     * 判定表（与 SLO.md §3.2 逐行对应）：
     * good=业务码 0；bad=5xxx 系统与依赖段；excluded=业务拒绝（1xxx/2xxx/3xxx/4xxx/6xxx/7xxx）。
     */
    private static final Map<ErrorCode, Outcome> SPEC = buildSpec();

    private static Map<ErrorCode, Outcome> buildSpec() {
        return new java.util.LinkedHashMap<>() {{
            // good：业务码 0（幂等命中 duplicated=true 也返回 0，走同一格）
            put(ErrorCode.SUCCESS, Outcome.GOOD);
            // bad：5xxx 系统与依赖段（含 FROZEN——防护性不提供也要烧预算，让它可见）
            put(ErrorCode.SYSTEM_ERROR, Outcome.BAD);
            put(ErrorCode.DEGRADED, Outcome.BAD);
            put(ErrorCode.FROZEN, Outcome.BAD);
            put(ErrorCode.REQUEST_BUDGET_EXHAUSTED, Outcome.BAD);
            // excluded：1xxx 参数与通用（1001 重放=调用方误用幂等键，非服务失败）
            put(ErrorCode.PARAM_ERROR, Outcome.EXCLUDED);
            put(ErrorCode.REQUEST_DUPLICATED, Outcome.EXCLUDED);
            put(ErrorCode.RESOURCE_NOT_FOUND, Outcome.EXCLUDED);
            // excluded：2xxx 模板域（状态类拒绝）
            put(ErrorCode.TEMPLATE_NOT_FOUND, Outcome.EXCLUDED);
            put(ErrorCode.TEMPLATE_NOT_RECEIVABLE, Outcome.EXCLUDED);
            put(ErrorCode.TEMPLATE_STATE_INVALID, Outcome.EXCLUDED);
            put(ErrorCode.ACTIVITY_NOT_FOUND, Outcome.EXCLUDED);
            // excluded：3xxx 领券域业务拒绝（售罄/限领/预算耗尽是正确答案）
            put(ErrorCode.STOCK_OUT, Outcome.EXCLUDED);
            put(ErrorCode.USER_LIMIT_EXCEEDED, Outcome.EXCLUDED);
            put(ErrorCode.DAILY_LIMIT_EXCEEDED, Outcome.EXCLUDED);
            put(ErrorCode.COUPON_NOT_USABLE, Outcome.EXCLUDED);
            put(ErrorCode.ISSUE_TASK_NOT_FOUND, Outcome.EXCLUDED);
            put(ErrorCode.GRANT_TASK_NOT_FOUND, Outcome.EXCLUDED);
            put(ErrorCode.CODE_INVALID, Outcome.EXCLUDED);
            put(ErrorCode.CODE_BATCH_NOT_FOUND, Outcome.EXCLUDED);
            put(ErrorCode.BUDGET_EXHAUSTED, Outcome.EXCLUDED);
            // excluded：4xxx 用券域状态冲突
            put(ErrorCode.USER_COUPON_NOT_FOUND, Outcome.EXCLUDED);
            put(ErrorCode.STATE_CONFLICT, Outcome.EXCLUDED);
            put(ErrorCode.USAGE_NOT_FOUND, Outcome.EXCLUDED);
            put(ErrorCode.USAGE_STATE_CONFLICT, Outcome.EXCLUDED);
            put(ErrorCode.COUPON_EXPIRED, Outcome.EXCLUDED);
            put(ErrorCode.USAGE_TIMEOUT_CLOSED, Outcome.EXCLUDED);
            put(ErrorCode.PRICING_SNAPSHOT_INVALID, Outcome.EXCLUDED);
            put(ErrorCode.REFUND_NOT_FOUND, Outcome.EXCLUDED);
            // excluded：6xxx 接入域（到不了测量点，归此桶只为全码段闭合）
            put(ErrorCode.SIGN_MISSING, Outcome.EXCLUDED);
            put(ErrorCode.APP_INVALID, Outcome.EXCLUDED);
            put(ErrorCode.SIGN_MISMATCH, Outcome.EXCLUDED);
            put(ErrorCode.TIMESTAMP_OUT_OF_WINDOW, Outcome.EXCLUDED);
            put(ErrorCode.NONCE_REPLAYED, Outcome.EXCLUDED);
            put(ErrorCode.QPS_LIMIT_EXCEEDED, Outcome.EXCLUDED);
            put(ErrorCode.DAILY_QUOTA_EXCEEDED, Outcome.EXCLUDED);
            put(ErrorCode.CLIENT_TOKEN_CONFLICT, Outcome.EXCLUDED);
            put(ErrorCode.CLIENT_TOKEN_PROCESSING, Outcome.EXCLUDED);
            put(ErrorCode.API_VERSION_UNSUPPORTED, Outcome.EXCLUDED);
            put(ErrorCode.RESOURCE_FORBIDDEN, Outcome.EXCLUDED);
            put(ErrorCode.CLIENT_TOKEN_MISSING, Outcome.EXCLUDED);
            put(ErrorCode.TENANT_NOT_ACTIVE, Outcome.EXCLUDED);
            // excluded：7xxx 安全域（7003 风控拒绝=服务正确地拒绝了）
            put(ErrorCode.RISK_REJECTED, Outcome.EXCLUDED);
            put(ErrorCode.ADMIN_SESSION_REQUIRED, Outcome.EXCLUDED);
            put(ErrorCode.ADMIN_LOGIN_FAILED, Outcome.EXCLUDED);
            put(ErrorCode.ADMIN_FORBIDDEN, Outcome.EXCLUDED);
        }};
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sli = new SliMetrics(registry);
    }

    @Test
    @DisplayName("判定表逐码：classify 与码表 SPEC 全量一致")
    void classifyMatchesSpecPerCode() {
        SPEC.forEach((code, expected) ->
                assertThat(SliMetrics.classify(code))
                        .as("错误码 %s(%d) 应判 %s", code, code.getCode(), expected)
                        .isEqualTo(expected));
    }

    @Test
    @DisplayName("码表完整性：SPEC 覆盖 ErrorCode 全部枚举值——新增错误码不更新 SLO 口径先红")
    void specCoversAllErrorCodes() {
        assertThat(SPEC.keySet()).as("码表必须与 ErrorCode 枚举一一对应")
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.asList(ErrorCode.values()));
    }

    @Test
    @DisplayName("null 错误码按 bad 保守处理（宁可多烧预算，不可漏记失败）")
    void nullCodeIsBad() {
        assertThat(SliMetrics.classify(null)).isEqualTo(Outcome.BAD);
    }

    @Test
    @DisplayName("四链路 × 三 outcome 代表码各计一次：journey/outcome 标签正确分桶")
    void fourJourneysThreeOutcomes() {
        for (Journey journey : Journey.values()) {
            sli.recordOutcome(journey, ErrorCode.SUCCESS);        // good
            sli.recordOutcome(journey, ErrorCode.STOCK_OUT);      // excluded（业务拒绝）
            sli.recordUncaught(journey);                          // bad（未捕获异常）
        }
        for (Journey journey : Journey.values()) {
            assertThat(count(journey, Outcome.GOOD)).isEqualTo(1);
            assertThat(count(journey, Outcome.BAD)).isEqualTo(1);
            assertThat(count(journey, Outcome.EXCLUDED)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("预建 12 条序列：构造后即存在（值 0），断流告警的 absent 语义才成立")
    void twelveSeriesPreCreatedAtConstruction() {
        for (Journey journey : Journey.values()) {
            for (Outcome outcome : Outcome.values()) {
                assertThat(count(journey, outcome))
                        .as("journey=%s outcome=%s 应预建且为 0", journey.tag(), outcome.tag())
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("计时：start/stop 落到对应 journey 的 Timer（全事件，不分 outcome）")
    void timerRecordsPerJourney() {
        for (Journey journey : Journey.values()) {
            Timer.Sample sample = sli.start();
            sli.recordOutcome(journey, ErrorCode.SUCCESS);
            sli.stop(sample, journey);
        }
        for (Journey journey : Journey.values()) {
            assertThat(registry.get("coupon.sli.duration").tag("journey", journey.tag())
                    .timer().count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("单实例口径声明：coupon_deployment_info 恒 1，标签 single/1/false")
    void deploymentInfoGauge() {
        assertThat(registry.get("coupon.deployment.info")
                .tag("topology", "single").tag("replicas", "1").tag("ha", "false")
                .gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("标签有界：SLI 指标只带 journey/outcome 两个枚举标签，无请求方可控维度")
    void tagsAreBounded() {
        java.util.Set<String> forbidden = java.util.Set.of(
                "userId", "orderNo", "requestId", "couponId", "templateId", "ip", "appId");
        assertThat(registry.getMeters()).allSatisfy(m ->
                assertThat(m.getId().getTags()).noneMatch(t -> forbidden.contains(t.getKey())));
        // journey/outcome 取值恒为枚举集合（4×3 封顶），构造即全部建出，无第 13 条可能
        assertThat(registry.find("coupon.sli.events").counters()).hasSize(12);
    }

    private double count(Journey journey, Outcome outcome) {
        return registry.get("coupon.sli.events")
                .tag("journey", journey.tag()).tag("outcome", outcome.tag())
                .counter().count();
    }
}
