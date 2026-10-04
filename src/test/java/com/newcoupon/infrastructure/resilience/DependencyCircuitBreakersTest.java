package com.newcoupon.infrastructure.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.newcoupon.application.service.TenantConfigService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 熔断判据的**异常形态**契约（S2 第 10 步 P1 修复）——<b>红绿灯</b>。
 *
 * <p><b>红</b>：本类先于修复写就。它喂的是**真实故障时真正抛出来的那个异常**，
 * 而现有白名单登记的是**内层**的 lettuce 异常——两者不是同一个类：
 *
 * <pre>
 * docker pause redis → TCP 还在、命令无响应
 *   → Lettuce 抛 io.lettuce.core.RedisCommandTimeoutException        （内层，白名单里有）
 *   → Spring Data Redis 翻译成 org.springframework.dao.QueryTimeoutException（外层，白名单里没有）
 *   → 熔断器失败计数恒为 0，永不打开
 * </pre>
 *
 * <p>红灯证据：日志中 {@code QueryTimeoutException} 在真实故障中出现 119 次；
 * 继承链用反射复核过——它与白名单里的 {@code TransientDataAccessResourceException} 是**兄弟类**，
 * 7 项白名单一项都不匹配。
 *
 * <p><b>为什么用单元测试而不是集成测试</b>：判据是纯函数式的契约（喂异常 → 看状态），
 * 不需要容器。秒级反馈让"先红后绿"的循环快得多，也不占 WSL 的容器资源。
 *
 * <p><b>为什么不用 {@code transitionToOpenState} 而用 {@code onError}</b>：
 * 前者测的是"打开之后的行为"，本类测的是"什么算失败"——后者才是出问题的那一环。
 */
class DependencyCircuitBreakersTest {

    /** 依赖级路径不读租户配置，用 mock 即可（本类只测判据） */
    private DependencyCircuitBreakers newBreakers() {
        return new DependencyCircuitBreakers(50f, 1000L, 30L, 10, 5, 3,
                mock(TenantConfigService.class));
    }

    /** 熔断器的窗口是 10、最小样本数 5：喂满 5 次即达判定条件 */
    private void feed(CircuitBreaker breaker, int times, Throwable error) {
        for (int i = 0; i < times; i++) {
            breaker.onError(0, TimeUnit.NANOSECONDS, error);
        }
    }

    // ------------------------------------------------------------------
    // ① 应当计入失败的真实形态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实形态①：Spring 包装后的 Redis 命令超时 → 计入失败、熔断打开（当前红）")
    void springWrappedRedisCommandTimeout_opensBreaker() {
        CircuitBreaker breaker = newBreakers().redis();

        // 这就是 docker pause / 主从切换时应用真正收到的那个异常：
        // 外层是 Spring 的 QueryTimeoutException，内层是 lettuce 的超时
        feed(breaker, 5, new org.springframework.dao.QueryTimeoutException(
                "Redis command timed out",
                new io.lettuce.core.RedisCommandTimeoutException("Command timed out after 1 second(s)")));

        assertThat(breaker.getState())
                .as("「命令超时」是熔断最该保护的形态——它必须计入失败")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("真实形态②：连接中断（RedisSystemException 包着 lettuce 连接异常）→ 计入失败（当前红）")
    void redisSystemExceptionWithConnectionCause_opensBreaker() {
        CircuitBreaker breaker = newBreakers().redis();

        // 日志中 709 次的形态：连接被断开时 Spring 包成 RedisSystemException
        feed(breaker, 5, new org.springframework.data.redis.RedisSystemException(
                "Redis connection failure",
                new io.lettuce.core.RedisConnectionException("Connection refused")));

        assertThat(breaker.getState())
                .as("连接中断必须计入——它的 cause 是白名单里的连接类")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("对照：白名单原样保留的形态仍然生效（不能修一个坏一个）")
    void previouslyCoveredShape_stillOpensBreaker() {
        CircuitBreaker breaker = newBreakers().redis();

        feed(breaker, 5, new SocketTimeoutException("模拟依赖超时"));

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ------------------------------------------------------------------
    // ② 不应当计入失败的形态（防"修过头"）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RedisSystemException 但 cause 是脚本执行错误 → **不计入**（它是我们的 bug，不是依赖故障）")
    void redisSystemExceptionWithScriptErrorCause_doesNotOpenBreaker() {
        CircuitBreaker breaker = newBreakers().redis();

        // 关键：**不能把 RedisSystemException 本身加进白名单**——它只是个"容器"，
        // 计入与否必须由 cause 决定。脚本错误（EVAL 语法/类型错）是我们自己的缺陷，
        // 把它算成"依赖不可用"会掩盖真正的 bug，还会误熔断。
        feed(breaker, 10, new org.springframework.data.redis.RedisSystemException(
                "ERR Error compiling script",
                new io.lettuce.core.RedisCommandExecutionException("ERR Error compiling script")));

        assertThat(breaker.getState())
                .as("脚本错误是应用缺陷，不该触发熔断").isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("业务异常（撞唯一索引）→ 不计入（既有契约，防止修复时被破坏）")
    void businessException_doesNotCount() {
        CircuitBreaker breaker = newBreakers().redis();

        feed(breaker, 10, new org.springframework.dao.DuplicateKeyException("幂等冲突"));

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("业务异常被包装了一层 → 仍不计入（沿原因链匹配不能把业务异常也捞进来）")
    void wrappedBusinessException_doesNotCount() {
        CircuitBreaker breaker = newBreakers().redis();

        // 真实场景：业务异常常被上层包装后再抛
        feed(breaker, 10, new org.springframework.dao.DataIntegrityViolationException(
                "duplicate",
                new org.springframework.dao.DuplicateKeyException("Duplicate entry for key uk_user_seq")));

        assertThat(breaker.getState())
                .as("沿原因链匹配的边界：捞的是'依赖故障'，不是'所有被包装过的异常'")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
