package com.newcoupon.infrastructure.metrics;

import com.newcoupon.common.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 四链路 SLI 指标（V6 阶段 5A）——SLO 的测量源。
 *
 * <p><b>为什么不用 HTTP 200 / {@code TradeMetrics.failed} 当 SLI</b>：HTTP 层只看传输成败，
 * {@code TradeMetrics} 只记核销/退款两域——它们都会把"业务正确拒绝"（售罄、限领、参数错）
 * 误算成服务失败。SLI 要回答的是"**服务**有没有把该做的事做对"，所以判定必须按业务码
 * 在服务边界做（{@link #classify}），这是 SLI 与既有运维指标的本质区别。
 *
 * <p><b>两条指标 + 一条拓扑声明</b>：
 * <ul>
 *   <li>{@code coupon_sli_events_total{journey,outcome}}：事件计数。journey 四值
 *       （receive/calculate/confirm/refund）、outcome 三值（good/bad/excluded）——
 *       全部是枚举标签，基数上界恒为 4×3=12 条序列。</li>
 *   <li>{@code coupon_sli_duration_seconds{journey} }：Timer。开百分位直方图
 *       （{@code publishPercentileHistogram}）+ 三个 SLO 档位桶（150/300/500ms，
 *       {@code serviceLevelObjectives}）——Prometheus 端用
 *       {@code histogram_quantile(0.99, ...)} 算分位，SLO 档位边界上是精确桶。</li>
 *   <li>{@code coupon_deployment_info{topology="single",replicas="1",ha="false"}}：恒 1 的
 *       Info 风格 Gauge——声明当前 SLO 的测量口径是**单实例、非 HA**（多实例后口径要重述）。</li>
 * </ul>
 *
 * <p><b>为什么这 12 条计数序列在构造时预建</b>（与 {@link GovernanceMetrics} 的
 * "首次 increment 才建序列"刻意相反）：
 * <ol>
 *   <li>SLI 有 absent 断流告警（规则 {@code CouponSliStreamMissing}）——序列从启动就存在，
 *       "消失"才能可靠地等价于"实例没了/抓取断了"，而不是"这会儿没流量"；</li>
 *   <li>全 bad 的链路（good 计数为 0）里 good 序列仍然存在，可用性算出 0 而不是
 *       "无数据"——PromQL 里不存在的序列与 0 值序列语义完全不同。</li>
 * </ol>
 * 代价是 12 条恒 0 序列，基数恒定，可接受。
 *
 * <p><b>接线位</b>：四个 AppService 公开方法（含异常出口），由 {@link SliAspect} 挂
 * {@link SliJourney} 注解统一包裹——与 V6 4A 风控决策日志同一类"服务边界"接线位。
 *
 * <p>口径全文（判定表逐码、excluded 不进分母的理由、错误预算政策）见
 * {@code docs/operations/SLO.md}——本类是它的代码侧实现，两处必须同步改。
 */
@Component
public class SliMetrics {

    private static final Logger log = LoggerFactory.getLogger(SliMetrics.class);

    /** 链路枚举——journey 标签的全部合法取值（加链路必须同时加 SLO 文档与规则文件） */
    public enum Journey {
        RECEIVE("receive"),
        CALCULATE("calculate"),
        CONFIRM("confirm"),
        REFUND("refund");

        private final String tag;

        Journey(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /**
     * SLI 结果三态——outcome 标签的全部合法取值。
     * good=服务做对了；bad=服务失败（烧错误预算）；excluded=服务**正确地拒绝了**
     * （业务拒绝，不进可用性分母——售罄风暴不应烧掉错误预算）。
     */
    public enum Outcome {
        GOOD("good"), BAD("bad"), EXCLUDED("excluded");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final MeterRegistry registry;
    private final Map<Journey, Timer> timers = new EnumMap<>(Journey.class);
    private final Map<Journey, Map<Outcome, Counter>> counters = new EnumMap<>(Journey.class);

    public SliMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Journey journey : Journey.values()) {
            counters.put(journey, new EnumMap<>(Outcome.class));
            for (Outcome outcome : Outcome.values()) {
                counters.get(journey).put(outcome, Counter.builder("coupon.sli.events")
                        .tag("journey", journey.tag())
                        .tag("outcome", outcome.tag())
                        .description("四链路 SLI 事件计数：good=业务码0成功；bad=5xxx系统与依赖失败/未捕获异常；"
                                + "excluded=业务拒绝（售罄/限领/参数错等，不进可用性分母）")
                        .register(registry));
            }
            timers.put(journey, Timer.builder("coupon.sli.duration")
                    .tag("journey", journey.tag())
                    .description("四链路服务边界耗时（全事件，含 bad/excluded）——SLI 延迟源，"
                            + "Prometheus 端 histogram_quantile(0.99) 计算")
                    // publishPercentileHistogram：发布全量累积桶（约 66 桶），任意分位可算；
                    // serviceLevelObjectives：在三条 SLO 档位（150/300/500ms）上额外发布精确桶，
                    // 保证 99.9%/300ms、99.95%/500ms 这类档位判定不被桶边界吞掉精度
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(Duration.ofMillis(150), Duration.ofMillis(300),
                            Duration.ofMillis(500))
                    .register(registry));
        }
        Gauge.builder("coupon.deployment.info", () -> 1)
                .tag("topology", "single")
                .tag("replicas", "1")
                .tag("ha", "false")
                .description("部署拓扑口径声明（Info 风格恒 1）：本 SLO 全部测量都在单实例、非 HA 口径下")
                .register(registry);
    }

    // ------------------------------------------------------------------
    // 计数（判定表）
    // ------------------------------------------------------------------

    /**
     * SLI 判定表（核心口径，代码与 SLO.md 必须同步）：
     * <ul>
     *   <li>good：业务码 0（成功）。幂等命中返回 duplicated=true 但业务码仍为 0 → good
     *       ——重放拿回旧结果就是服务的正确行为。</li>
     *   <li>bad：5xxx 段（系统与依赖）。SYSTEM_ERROR(5000)/DEGRADED(5001)/
     *       REQUEST_BUDGET_EXHAUSTED(5003=超时族) 都是"服务没做到"；FROZEN(5002)
     *       刻意归 bad：冻结是**防护动作触发的不提供**，不是正常业务终点——冻结期间
     *       用户确实领不到券，让它烧预算才能在监控上看见（与售罄=正常终点相反）。</li>
     *   <li>excluded：其余全部（1xxx 参数/通用、2xxx 模板态、3xxx 领券域业务拒绝、
     *       4xxx 用券域状态冲突、6xxx 接入层、7xxx 安全域）——"服务正确地拒绝了"。
     *       注意 6xxx 实际到不了这里（接入过滤器在服务边界之前就拒了，不进 SLI），
     *       归 excluded 只是让判定表对全码段闭合、新增业务码自动落对桶。</li>
     * </ul>
     * 未捕获的非 BizException 异常走 {@link #recordUncaught}（bad），不经过本方法。
     *
     * @param code 业务错误码；null 意味着出现了不合规的异常构造——按 bad 保守处理
     *             （宁可多烧预算引发关注，不可漏记服务失败）
     */
    public static Outcome classify(ErrorCode code) {
        if (code == null) {
            return Outcome.BAD;
        }
        if (code == ErrorCode.SUCCESS) {
            return Outcome.GOOD;
        }
        int value = code.getCode();
        if (value >= 5000 && value < 6000) {
            return Outcome.BAD;
        }
        return Outcome.EXCLUDED;
    }

    /** 记一次链路结果（good 路径传 {@link ErrorCode#SUCCESS}；BizException 出口传其错误码） */
    public void recordOutcome(Journey journey, ErrorCode code) {
        safeIncrement(journey, classify(code));
    }

    /** 记一次未捕获异常（非 BizException）——按判定表口径恒为 bad */
    public void recordUncaught(Journey journey) {
        safeIncrement(journey, Outcome.BAD);
    }

    // ------------------------------------------------------------------
    // 计时
    // ------------------------------------------------------------------

    /** 开始一次链路计时（与 {@link #stop} 配对；由 SliAspect 在方法边界调用） */
    public Timer.Sample start() {
        return Timer.start(registry);
    }

    /** 结束计时并落到该 journey 的 Timer——在 finally 里调用，成败路径都计时 */
    public void stop(Timer.Sample sample, Journey journey) {
        sample.stop(timers.get(journey));
    }

    // ------------------------------------------------------------------

    /** 指标失败绝不能影响业务（同 TradeAppService.safeMetric 的纪律）；失败只留 WARN */
    private void safeIncrement(Journey journey, Outcome outcome) {
        try {
            counters.get(journey).get(outcome).increment();
        } catch (RuntimeException e) {
            log.warn("sli metric increment failed: journey={} outcome={}", journey.tag(), outcome.tag(), e);
        }
    }
}
