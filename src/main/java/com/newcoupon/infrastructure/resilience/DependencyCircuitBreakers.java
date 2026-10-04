package com.newcoupon.infrastructure.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
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

    public DependencyCircuitBreakers(
            @Value("${coupon.circuit-breaker.failure-rate:50}") float failureRate,
            @Value("${coupon.circuit-breaker.slow-call-ms:1000}") long slowCallMs,
            @Value("${coupon.circuit-breaker.open-seconds:30}") long openSeconds,
            @Value("${coupon.circuit-breaker.window-size:10}") int windowSize,
            @Value("${coupon.circuit-breaker.min-calls:5}") int minCalls,
            @Value("${coupon.circuit-breaker.half-open-calls:3}") int halfOpenCalls) {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRate)                        // 异常比例超过这个值就打开
                .slowCallDurationThreshold(Duration.ofMillis(slowCallMs)) // 多慢算"慢调用"
                .slowCallRateThreshold(failureRate)                      // 慢调用比例超过它也打开
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds)) // 打开后等多久放试探请求
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
                        java.util.concurrent.TimeoutException.class)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
    }

    public CircuitBreaker redis() {
        return registry.circuitBreaker(REDIS);
    }

    public CircuitBreaker mysql() {
        return registry.circuitBreaker(MYSQL);
    }

    /** 供运维/测试直接操作状态（如把某个熔断器手动打开或复位） */
    public CircuitBreakerRegistry registry() {
        return registry;
    }
}
