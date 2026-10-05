package com.newcoupon.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 治理指标（S2 第 11 步，RQ-10）——限流命中、限流失效、租户用量、熔断切换。
 *
 * <p><b>与 {@code ReceiveMetrics} 并存而不合并</b>：那个类属于领券域（它的指标名是
 * {@code coupon_receive_*}，调用方是 {@code ReceiveAppService}）；本类属于治理域，
 * 调用方是过滤器与熔断器。合并会让人为一个域类承担另一域的职责。
 * 命名上统一用 {@code coupon_} 前缀，官方 resilience4j 集成产出的
 * {@code resilience4j_circuitbreaker_*} 保持原名（上游大盘示例都按它写，改名收益为零）。
 *
 * <p><b>标签基数——本类最需要守住的东西</b>：每个标签组合 = 一条独立的时间序列。
 * 三条纪律：
 * <ol>
 *   <li><b>绝不把 IP、资源全名放进标签</b>：它们是<b>请求方可控的无界维度</b>——
 *       随便换 IP 或换路径参数就能让序列数无限增长（cardinality DoS，
 *       表现为 Prometheus 内存爆掉、抓取变慢）。限流指标只记**维度名**（{@code ip}/{@code risk_ip}/
 *       {@code resource}/{@code tenant_qps}/{@code tenant_daily}），具体是谁被限了去查审计表。</li>
 *   <li><b>appId 只在验签通过时作标签</b>：未验签的请求一律归入固定桶 {@code unverified}——
 *       否则任何人用随机 appId 发请求就能把序列打爆（请求头里的 appId 在验签前**不可信**）。</li>
 *   <li><b>熔断器的 name 标签**有界**</b>：依赖级只有 {@code redis}/{@code mysql}；
 *       租户级虽按 appId 命名，但只有真的发生过状态转移的才会创建计数器——
 *       基数 = "出过问题的租户数"，而不是"全部租户数"。</li>
 * </ol>
 */
@Component
public class GovernanceMetrics {

    /** 未验签请求的固定标签值（见类注释第 2 条） */
    public static final String UNVERIFIED = "unverified";

    private final MeterRegistry registry;

    public GovernanceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ------------------------------------------------------------------

    /**
     * 一次限流拒绝。
     *
     * @param dimension **维度名**，不是具体的 IP/资源值——见类注释第 1 条
     */
    public void recordLimitRejected(String dimension) {
        Counter.builder("coupon_ratelimit_rejected_total")
                .tag("dimension", dimension)
                .description("被限流拒绝的请求数（按维度；不含 IP/资源值——那是攻击者可控的无界维度）")
                .register(registry)
                .increment();
    }

    /**
     * 限流检查因依赖故障而**放行**（fail-open）。
     *
     * <p><b>为什么这条指标不可省</b>：限流是 fail-open 的（Redis 挂了就放行，可用性优先）。
     * 于是故障期间仪表盘上会呈现"<b>拒绝数为 0、一切正常</b>"的假象——
     * 实际上防线已经失效。没有这条指标，**防线失效与"流量本来就小"在监控上完全无法区分**。
     */
    public void recordLimitFailOpen(String dimension) {
        Counter.builder("coupon_ratelimit_failopen_total")
                .tag("dimension", dimension)
                .description("限流检查因依赖故障而放行的次数——它开始涨说明防线暂时失效")
                .register(registry)
                .increment();
    }

    /**
     * 一次开放面调用（租户用量）。
     *
     * @param appId   **验签通过**的接入方；未验签传 {@code null}（会归入固定桶）
     * @param outcome 结果粗分类（{@code ok} / {@code rejected} / {@code degraded}），
     *                粒度刻意粗——细粒度结果分布已有 {@code ReceiveMetrics} 与审计表
     */
    public void recordOpenApiCall(String appId, String outcome) {
        Counter.builder("coupon_open_api_calls_total")
                .tag("app_id", (appId == null || appId.isBlank()) ? UNVERIFIED : appId)
                .tag("outcome", outcome)
                .description("开放面调用数（app_id 标签基数有界于已注册租户数；未验签归入 unverified）")
                .register(registry)
                .increment();
    }

    /**
     * 熔断器状态转移。
     *
     * <p>官方 resilience4j 集成导出 state / failure_rate / calls 等 16 项指标，
     * 但**没有任何 transition 计数**；而 RQ-10 明确要"切换次数"。
     * 挂在熔断器已有的 {@code onStateTransition} 回调上——那是**状态事件**，
     * 不是调用路径，所以不会被请求量放大。
     */
    public void recordBreakerTransition(String name, String toState) {
        Counter.builder("coupon_circuit_breaker_transitions_total")
                .tag("name", name)
                .tag("to_state", toState == null ? "unknown" : toState.toLowerCase())
                .description("熔断器状态转移次数（官方集成不提供此项）")
                .register(registry)
                .increment();
    }
}
