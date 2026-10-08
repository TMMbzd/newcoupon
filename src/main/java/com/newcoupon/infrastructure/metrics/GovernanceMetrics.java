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
     * 集群 token 请求**降级**（第 9 步）。
     *
     * <p>什么时候会涨：连不上 Token Server / 超时 / 服务端没有这条规则 / 请求非法。
     * 这些情况都会让规则**静默回退到本地单机限制**——也就是**跨实例精确当场失效**
     * （两个实例合计 2 倍配额）。这个计数是唯一能让它可见的地方。
     *
     * @param reason 有界的状态码标签（fail / too_many / no_rule / …），不是异常消息
     */
    public void recordClusterTokenFallback(String reason) {
        Counter.builder("coupon_sentinel_cluster_token_fallback_total")
                .tag("reason", reason)
                .description("集群 token 降级次数——开始涨说明集群流控已经退化成单机（合计配额会翻倍）")
                .register(registry)
                .increment();
    }

    /**
     * Sentinel 处理链**内部异常**（迁移第 7 步）。
     *
     * <p>为什么它必须存在：链里抛出的非 {@code BlockException} 会被 {@code CtSph} 的
     * {@code catch (Throwable)} 吞掉并**放行**——也就是说这一发的限流判定实际上没生效，
     * 而它在应用日志与指标里原本一点痕迹都没有（只写 Sentinel 自己的日志文件）。
     *
     * <p>**零标签**是刻意的：这里的维度要么是资源名、要么是异常类型，基数无法先验保证；
     * 按项目的基数纪律，不确定就不加标签，具体是谁出问题去看日志。
     */
    public void recordSentinelInternalError() {
        Counter.builder("coupon_sentinel_internal_error_total")
                .description("Sentinel 处理链内部异常次数——每一次都意味着那一发请求的限流判定没生效")
                .register(registry)
                .increment();
    }

    /**
     * 被预检丢弃的 Sentinel 规则数（迁移第 7 步）。
     *
     * <p>Sentinel 对过不了 {@code isValidRule} 的规则**只写一行 WARN 到它自己的日志文件**
     * （{@code ~/logs/csp/sentinel-record.log}，**不进应用日志**）——于是"管理面显示保存成功、
     * 运行时静默无效"。我们已经做了预检（{@code TenantFlowRuleMapper}），
     * 但那个 {@code dropped} 列表在调用点被丢掉了，等于白预检。这里把它接进指标。
     */
    public void recordSentinelRulesDropped(int count) {
        if (count <= 0) {
            return;   // 没丢就不创建时间序列（否则每个启动都会多一条恒为 0 的序列）
        }
        Counter.builder("coupon_sentinel_rules_dropped_total")
                .description("被预检丢弃的 Sentinel 规则数——它们**不会生效**，必须能被看见")
                .register(registry)
                .increment(count);
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
