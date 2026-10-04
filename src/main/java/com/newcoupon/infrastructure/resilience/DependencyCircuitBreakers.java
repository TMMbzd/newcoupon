package com.newcoupon.infrastructure.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import org.springframework.stereotype.Component;

/**
 * 依赖级熔断器（S2 第 7 步）——"总闸"。
 *
 * <p><b>要解决什么</b>：Redis 或数据库不可用时，每个请求都会卡在超时上等满（本项目配的是 1 秒 / 3 秒）。
 * 一个请求等 3 秒不算什么，但 QPS 上千时，请求线程会在几秒内被耗光——<b>一个依赖的故障被放大成整个服务不可用</b>。
 * 熔断的作用就是"发现依赖不行了，别再打它"，让失败变成瞬间返回。
 *
 * <p><b>两层熔断的分工</b>：本类是**依赖级**（总闸）——保护依赖本身，所有租户共用一个判断；
 * 后续的**租户级**（分闸）负责识别"是谁在制造异常"并把它单独拦下。先看整体，再看个体。
 *
 * <p><b>为什么用编程式而不是 {@code @CircuitBreaker} 注解</b>：熔断要加在**具体的基础设施调用点**
 * （一次 Redis 闸门、一次数据库扣账），而不是 Controller 方法上；编程式的注册表还能按名字给出
 * 不同配置（租户级熔断就要用这个能力）。
 */
@Component
public class DependencyCircuitBreakers {

    /** 与 Redis 相关的调用（库存闸门是唯一的强依赖点） */
    public static final String REDIS = "redis";
    /** 与数据库相关的调用 */
    public static final String MYSQL = "mysql";

    private final CircuitBreakerRegistry registry;
    private final CircuitBreakerConfig defaultConfig;
    /** 两层共用的判据参数（依赖级的默认配置与租户级配置都从这里取） */
    private final int halfOpenCalls;
    private final int windowSize;
    private final int minCalls;
    /** 租户级熔断器实例缓存：名字 → 实例（配置改了要重启才生效，见 forTenant 注释） */
    private final java.util.Map<String, CircuitBreaker> tenantBreakers = new java.util.concurrent.ConcurrentHashMap<>();
    private final TenantConfigService tenantConfig;

    public DependencyCircuitBreakers(
            @Value("${coupon.circuit-breaker.failure-rate:50}") float failureRate,
            @Value("${coupon.circuit-breaker.slow-call-ms:1000}") long slowCallMs,
            @Value("${coupon.circuit-breaker.open-seconds:30}") long openSeconds,
            @Value("${coupon.circuit-breaker.window-size:10}") int windowSize,
            @Value("${coupon.circuit-breaker.min-calls:5}") int minCalls,
            @Value("${coupon.circuit-breaker.half-open-calls:3}") int halfOpenCalls,
            TenantConfigService tenantConfig) {
        this.tenantConfig = tenantConfig;
        this.halfOpenCalls = halfOpenCalls;
        this.windowSize = windowSize;
        this.minCalls = minCalls;

        CircuitBreakerConfig config = baseConfig()
                .failureRateThreshold(failureRate)                        // 异常比例超过这个值就打开
                .slowCallDurationThreshold(Duration.ofMillis(slowCallMs)) // 多慢算"慢调用"
                .slowCallRateThreshold(failureRate)                      // 慢调用比例超过它也打开
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds)) // 打开后等多久放试探请求
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
        this.defaultConfig = config;
    }

    /** 依赖级的默认配置（供需要时查询） */
    public CircuitBreakerConfig defaultConfig() {
        return defaultConfig;
    }

    /** 租户级熔断器：同一套判据，只是阈值换成了该租户配置的 */
    private CircuitBreakerConfig config(int failureRate, long slowCallMs, long openSeconds) {
        return baseConfig()
                .failureRateThreshold(failureRate)
                .slowCallDurationThreshold(Duration.ofMillis(slowCallMs))
                .slowCallRateThreshold(failureRate)
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minCalls)
                .build();
    }

    /**
     * 依赖级与租户级**共用**的判据部分：窗口大小、最小样本数、半开探测数、以及"什么算失败"。
     *
     * <p>抽出来是为了让两层的行为**只在阈值上不同**——否则很容易出现"租户级没配 recordExceptions、
     * 于是业务异常又被算成失败"这类只有一层才会犯的错。
     */
    private CircuitBreakerConfig.Builder baseConfig() {
        return CircuitBreakerConfig.custom()
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)    // 半开状态放几个试探
                .slidingWindowSize(windowSize)                           // 用最近多少次调用统计
                .minimumNumberOfCalls(minCalls)                          // 样本不足时先不判（避免开局误熔断）
                // **只把"真正的依赖故障"算作失败**（白名单），其余一律不计。
                //
                // 这里用白名单而不是"忽略业务异常"的黑名单，是因为黑名单会**漏**：
                // 实测就被 DuplicateKeyException（幂等/限领撞唯一索引，是正常的业务结果）
                // 累计到阈值、把熔断器打开了，导致后续用例全部"系统繁忙"。
                // 白名单的方向是安全的：新增一种业务异常时它自动不计入；
                // 而漏记某种依赖故障的代价只是"熔断晚一点生效"，远小于"被业务异常误熔断"。
                .recordExceptions(
                        org.springframework.dao.DataAccessResourceFailureException.class,   // 连不上/拿不到连接
                        org.springframework.dao.TransientDataAccessResourceException.class, // 瞬时资源问题（含查询超时）
                        org.springframework.data.redis.RedisConnectionFailureException.class,
                        io.lettuce.core.RedisConnectionException.class,
                        io.lettuce.core.RedisCommandTimeoutException.class,
                        java.net.SocketTimeoutException.class,
                        java.util.concurrent.TimeoutException.class);
    }

    /** 依赖级熔断器（总闸）：按依赖名取 */
    public CircuitBreaker dependency(String dependency) {
        return registry.circuitBreaker(dependency);
    }

    public CircuitBreaker redis() {
        return dependency(REDIS);
    }

    public CircuitBreaker mysql() {
        return dependency(MYSQL);
    }

    /**
     * 取某个租户在某个依赖上的熔断器（S2 第 8 步，**分闸**）。
     *
     * <p>名字里带 appId → 每个租户各有一份独立的失败统计，
     * 于是"某个租户把依赖打出异常"时被隔离的是它自己，别家不受影响。
     *
     * <p>阈值来自**租户配置**（异常比例 / 慢调用界线 / 打开时长），没配则用全局默认——
     * 于是"给某个客户放宽熔断阈值"变成一条配置，而不是一次发版。
     *
     * <p><b>实例按名字缓存</b>：{@code registry.circuitBreaker(name, config)} 对已存在的名字会
     * 忽略新配置，所以这里显式缓存，让"配置改了何时生效"变成一个看得见的事实
     * （当前：进程内首次创建时读一次；要改就重启或调用 {@link #evictTenant}）。
     *
     * <p><b>容量</b>：实例数 = 依赖数 × 租户数，每个实例只有一个滑动窗口的计数，开销很小；
     * 租户数上千时需要加清理（已登记 L55）。
     */
    public CircuitBreaker forTenant(String dependency, String appId) {
        String name = dependency + ":" + appId;
        return tenantBreakers.computeIfAbsent(name, n -> {
            int failureRate = tenantConfig.getInt(appId, TenantConfigKey.CIRCUIT_BREAKER_FAILURE_RATE);
            long slowCallMs = tenantConfig.getInt(appId, TenantConfigKey.CIRCUIT_BREAKER_SLOW_CALL_MS);
            long openSeconds = tenantConfig.getInt(appId, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS);
            return registry.circuitBreaker(n, config(failureRate, slowCallMs, openSeconds));
        });
    }

    /** 清掉某个租户的熔断器实例——配置改动后想让新阈值立刻生效时用 */
    public void evictTenant(String dependency, String appId) {
        String name = dependency + ":" + appId;
        tenantBreakers.remove(name);
        registry.remove(name);
    }

    /** 供运维/测试直接操作状态（如把某个熔断器手动打开或复位） */
    public CircuitBreakerRegistry registry() {
        return registry;
    }
}
