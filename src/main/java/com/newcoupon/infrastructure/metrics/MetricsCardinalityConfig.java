package com.newcoupon.infrastructure.metrics;

import io.micrometer.core.instrument.config.MeterFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标基数防线装配（V6 5A）——把 {@link MetricsCardinalityGuard} 与
 * Micrometer 内建的 {@code MeterFilter.maximumAllowableTags} 注册成全局 MeterFilter。
 *
 * <p><b>两层各自拦什么</b>：
 * <ul>
 *   <li>{@link #metricsCardinalityGuard}：每米标签数 ≤6（Micrometer 1.13.6 的
 *       http.server.requests 实际 6 标签，含 DefaultMeterObservationHandler 追加的
 *       error——见 {@link MetricsCardinalityGuard} 类注释）、全 registry 真实米数
 *       ≤5000（实时数 registry，被拒的米不计数，事故恢复即解封）——兜"总量失控"
 *       （新指标类型混入、批量注册事故）。</li>
 *   <li>{@link #forbiddenTagValueCaps()} 系列六个内建过滤器：对**危险标签键**
 *       （userId/orderNo/requestId/couponId/templateId/ip——V6 §5A 点名的无界维度）
 *       限"单键取值数 ≤{@value #MAX_VALUES_PER_FORBIDDEN_KEY}"——合法指标根本不该带
 *       这些键，出现第 33 个取值即 WARN+拒新，把"某个新指标偷偷把 userId 塞进了标签"
 *       这类事故钉死在 32 条序列。</li>
 * </ul>
 *
 * <p><b>为什么单独一个配置类、registry 经 {@code MeterRegistryCustomizer} 交进 guard</b>：
 * 与 {@link CircuitBreakerCardinalityConfig} 同一条实测教训——MeterFilter bean 会在
 * MeterRegistry 创建期被收集，过滤器**直接注入** MeterRegistry 就是创建期循环；
 * 在 accept() 里惰性解析 ObjectProvider 也不行（Micrometer meterMap 锁内的 Spring
 * 单例查找=锁序反转，实测挂死启动）。customizer 回调在过滤器之前、registry 创建之后
 * 的安全窗口执行，只存一个 volatile 引用。
 */
@Configuration
public class MetricsCardinalityConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsCardinalityConfig.class);

    /** 危险标签键的单键取值上限：合法指标不该带这些键，出现即事故信号，取小值 */
    static final int MAX_VALUES_PER_FORBIDDEN_KEY = 32;

    /** 拒绝告警限流（每 100 次记一条）：基数爆炸时拒绝本身高频，不限流会把日志打成第二个事故现场 */
    private static final java.util.concurrent.atomic.AtomicLong WARN_COUNTER =
            new java.util.concurrent.atomic.AtomicLong();

    @Bean
    MetricsCardinalityGuard metricsCardinalityGuard() {
        return new MetricsCardinalityGuard();
    }

    /**
     * 把 registry 引用交给 guard 的钩子：MeterRegistryCustomizer 在 registry 创建后、
     * 过滤器与绑定器应用前的回调线程上执行（Boot 的 MeterRegistryConfigurer 顺序：
     * customizers → filters → binders）。**不能**用注入/Provider 在 accept() 里惰性解析
     * ——accept() 在 Micrometer meterMap 锁内被调用，再进 Spring 单例查找就是锁序反转
     * （2026-11-05 实测把上下文启动挂死，见 MetricsCardinalityGuard 类注释"attach"段）。
     */
    @Bean
    org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer<
            io.micrometer.core.instrument.MeterRegistry> cardinalityGuardAttacher(
                    MetricsCardinalityGuard guard) {
        return guard::attach;
    }

    // ---- 危险标签键逐键封顶（Micrometer 内建 maximumAllowableTags：前缀 ""=全部米）----

    @Bean
    MeterFilter userIdTagValueCap() {
        return forbiddenTagValueCap("userId");
    }

    @Bean
    MeterFilter orderNoTagValueCap() {
        return forbiddenTagValueCap("orderNo");
    }

    @Bean
    MeterFilter requestIdTagValueCap() {
        return forbiddenTagValueCap("requestId");
    }

    @Bean
    MeterFilter couponIdTagValueCap() {
        return forbiddenTagValueCap("couponId");
    }

    @Bean
    MeterFilter templateIdTagValueCap() {
        return forbiddenTagValueCap("templateId");
    }

    @Bean
    MeterFilter ipTagValueCap() {
        return forbiddenTagValueCap("ip");
    }

    /**
     * 单键取值封顶：超过 {@link #MAX_VALUES_PER_FORBIDDEN_KEY} 个不同取值后，
     * 带该键**新取值**的米被 WARN+DENY（既有取值不受影响——闸门止血，不追杀存量）。
     */
    private static MeterFilter forbiddenTagValueCap(String tagKey) {
        // onMaxReached 是"超限后对新取值生效的过滤器"——MeterFilter 不是函数式接口
        // （全默认方法），这里用匿名类实现"WARN + 拒新"
        return MeterFilter.maximumAllowableTags("", tagKey, MAX_VALUES_PER_FORBIDDEN_KEY,
                new MeterFilter() {
                    @Override
                    public io.micrometer.core.instrument.config.MeterFilterReply accept(
                            io.micrometer.core.instrument.Meter.Id id) {
                        long denied = WARN_COUNTER.incrementAndGet();
                        if (denied == 1 || denied % 100 == 0) {
                            log.warn("METRICS CARDINALITY GUARD 拒绝注册 meter={}：标签 {} 取值数超上限 {}"
                                            + "（累计拒绝 {} 次）——请求方可控维度进了标签"
                                            + "（cardinality DoS 入口），请立即排查",
                                    id.getName(), tagKey, MAX_VALUES_PER_FORBIDDEN_KEY, denied);
                        }
                        return io.micrometer.core.instrument.config.MeterFilterReply.DENY;
                    }
                });
    }
}
