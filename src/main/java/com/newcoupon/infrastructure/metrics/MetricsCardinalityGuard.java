package com.newcoupon.infrastructure.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
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
 * <p><b>每米标签数上限为什么是 6（审查 critical 修正）</b>：Spring Boot 3.3.5 自带的
 * {@code http.server.requests} 在 Micrometer 1.13.6 下实际带 **6** 个标签——
 * {@code method/uri/status/exception/outcome} 之外，
 * {@code DefaultMeterObservationHandler} 会把观测值的 error 键再追加为第 6 个
 * {@code error} 标签。按 5 设限会在**首个 HTTP 请求**上把这条核心指标整族拒掉，
 * 且每请求都重走一遍拒新链。6 覆盖当前真实形态；上限仍是防线——真正无界的
 * userId/orderNo 标签（每请求一个新值）照样在单键取值闸（
 * {@link MetricsCardinalityConfig}）被拦。防线由
 * {@code SliMetricsIntegrationTest#cardinalityGuardDoesNotKillBuiltInHttpMetrics}
 * 钉住：若未来 Micrometer 加到第 7 个标签，该测试先红，提示重新校准上限。
 *
 * <p><b>总米数为什么实时数 registry 而不是计数器累加（审查 minor 修正）</b>：
 * 计数器版在"每次 accept 都自增且不回退"下有两个缺陷——被其他过滤器拒掉的注册
 * 也被计入；一次基数事故（如 userId 进标签，每请求一个新值）在几千个请求内就把
 * 5000 顶穿，之后**全 registry 永久拒新**，回滚缺陷也不恢复。改为注册时实时读
 * {@code registry.getMeters().size()}（ConcurrentHashMap 拷贝，无锁）：被拒的米
 * 从未进 registry，计数天然只对真实米数生效，事故恢复后闸门自动解除。代价是每次
 * 注册尝试一次 O(n) 遍历——注册不是热路径（只有新米首次注册），兜底闸门可承受。
 *
 * <p><b>registry 引用为什么用 attach 而不是注入/Provider 解析（2026-11-05 实测坑）</b>：
 * MeterFilter 直接构造注入 MeterRegistry 会成创建期循环；在 accept() 里惰性解析
 * ObjectProvider 也不行——accept() 在 Micrometer 的 meterMap 锁**内部**被调用，
 * 解析 provider 要走 Spring 单例查找，与持单例锁注册指标的线程构成锁序反转，
 * 实测把上下文启动挂死。正确做法：由 Boot 的
 * {@code MeterRegistryCustomizer}（在过滤器与绑定器之前、registry 创建后回调）
 * 调 {@link #attach} 存一个 volatile 引用——accept() 只读 volatile，零 Spring 交互。
 *
 * <p><b>为什么上限取这么松</b>（tags≤6/米、总米数≤5000）：兜底只该拦"失控"，
 * 不该拦"正常生长"。5000 远高于当前全量米数（数百量级），又远低于 Prometheus
 * 单实例的舒适区（数万序列）。
 */
public final class MetricsCardinalityGuard implements MeterFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsCardinalityGuard.class);

    /**
     * 每米标签数上限。来源（2026-11-05 实测）：Spring Boot 3.3.5 / Micrometer 1.13.6 的
     * http.server.requests 实际 6 标签（DefaultMeterObservationHandler 追加 error）。
     */
    static final int DEFAULT_MAX_TAGS_PER_METER = 6;
    /** 全 registry 米数上限 */
    static final int DEFAULT_MAX_METERS = 5000;

    private final int maxTagsPerMeter;
    private final int maxMeters;
    /** attach 前为 null：启动极早期（理论上不可达）总量检查跳过（fail-open，不挡启动） */
    private final java.util.concurrent.atomic.AtomicReference<MeterRegistry> registryRef =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicLong deniedCount = new AtomicLong();

    public MetricsCardinalityGuard() {
        this(DEFAULT_MAX_TAGS_PER_METER, DEFAULT_MAX_METERS);
    }

    /** 小上限构造器：单测用它把闸门拧紧到可断言的量级 */
    MetricsCardinalityGuard(int maxTagsPerMeter, int maxMeters) {
        this.maxTagsPerMeter = maxTagsPerMeter;
        this.maxMeters = maxMeters;
    }

    /**
     * 由 {@code MeterRegistryCustomizer} 在 registry 创建后回调存引用——
     * 见类注释"attach"段：不能经注入/Provider 拿，锁序会死。
     */
    public void attach(MeterRegistry registry) {
        registryRef.set(registry);
    }

    @Override
    public MeterFilterReply accept(Meter.Id id) {
        int tagCount = id.getTags().size();
        if (tagCount > maxTagsPerMeter) {
            warn(id, "标签数 " + tagCount + " 超上限 " + maxTagsPerMeter);
            return MeterFilterReply.DENY;
        }
        // 实时数真实米数（见类注释"总米数"段）：被本闸或单键闸拒掉的米从未注册，
        // 不会撑大这个数——事故恢复即解除，不存在"顶穿后永久拒新"。
        // 只读 volatile + getMeters()（ConcurrentHashMap 拷贝），不进任何 Spring 锁
        MeterRegistry registry = registryRef.get();
        if (registry != null && registry.getMeters().size() >= maxMeters) {
            warn(id, "全 registry 米数达上限 " + maxMeters);
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
