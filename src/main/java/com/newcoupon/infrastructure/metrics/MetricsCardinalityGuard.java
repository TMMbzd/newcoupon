package com.newcoupon.infrastructure.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 指标基数最后闸门（V6 5A）：每米标签数与全 registry 米数两个"宽松上限"，
 * 超限 WARN + 拒新（DENY）。
 *
 * <p><b>防的是什么</b>：cardinality DoS——任何一个把请求方可控值（userId/IP/单号）
 * 塞进标签的失误，都会让时间序列数随流量无限增长，最终把 Prometheus 内存打爆。
 * 第一道防线是"写代码时就不加无界标签"（见 {@link GovernanceMetrics} 类注释三条纪律），
 * 本类是**兜底**：假设第一道防线被破，这里把爆炸半径钉死在常数级。
 *
 * <p><b>为什么上限取这么松</b>（tags≤5/米、总米数≤5000）：兜底只该拦"失控"，
 * 不该拦"正常生长"。tags=5 覆盖 Spring 自带 {@code http.server.requests} 的五个标签
 * （method/uri/status/exception/outcome）——再紧一格就会误杀它；5000 远高于当前
 * 全量米数（数百量级），又远低于 Prometheus 单实例的舒适区（数万序列）。
 *
 * <p><b>计数语义（刻意保守）</b>：按"注册尝试次数"累加而不是精确集合去重——
 * 同一米被移除后重注册会重复计数，代价是闸门**提前**触发而不是漏放。
 * 对兜底闸门而言，提前拦比漏放安全。
 */
public final class MetricsCardinalityGuard implements MeterFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsCardinalityGuard.class);

    /** 每米标签数上限（Spring http.server.requests 恰为 5：method/uri/status/exception/outcome） */
    static final int DEFAULT_MAX_TAGS_PER_METER = 5;
    /** 全 registry 米数上限 */
    static final int DEFAULT_MAX_METERS = 5000;

    private final int maxTagsPerMeter;
    private final int maxMeters;
    private final AtomicInteger acceptedAttempts = new AtomicInteger();
    private final AtomicLong deniedCount = new AtomicLong();

    public MetricsCardinalityGuard() {
        this(DEFAULT_MAX_TAGS_PER_METER, DEFAULT_MAX_METERS);
    }

    /** 小上限构造器：单测用它把闸门拧紧到可断言的量级 */
    MetricsCardinalityGuard(int maxTagsPerMeter, int maxMeters) {
        this.maxTagsPerMeter = maxTagsPerMeter;
        this.maxMeters = maxMeters;
    }

    @Override
    public MeterFilterReply accept(Meter.Id id) {
        int tagCount = id.getTags().size();
        if (tagCount > maxTagsPerMeter) {
            warn(id, "标签数 " + tagCount + " 超上限 " + maxTagsPerMeter);
            return MeterFilterReply.DENY;
        }
        if (acceptedAttempts.incrementAndGet() > maxMeters) {
            warn(id, "全 registry 米数超上限 " + maxMeters);
            return MeterFilterReply.DENY;
        }
        return MeterFilterReply.NEUTRAL;
    }

    /** WARN 但限流（每 100 次拒绝记一条，避免攻击流量把日志也打成 DoS） */
    private void warn(Meter.Id id, String reason) {
        long denied = deniedCount.incrementAndGet();
        if (denied == 1 || denied % 100 == 0) {
            log.warn("METRICS CARDINALITY GUARD 拒绝注册 meter={}（{}；累计拒绝 {} 次）——"
                            + "疑似无界标签混入，请对照 GovernanceMetrics 的基数纪律排查",
                    id.getName(), reason, denied);
        }
    }
}
