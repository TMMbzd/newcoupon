package com.newcoupon.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IP 维度检查器（迁移第 6 步，2026-10-08）——<b>红绿灯</b>。
 *
 * <p><b>红在"限流速率只有配置值的一半"</b>：迁移前调用处传的窗口是
 * {@code Duration.ofSeconds(2)}，而窗口在令牌桶里的含义是**补充周期**
 * （capacity 个令牌 / 每 window 补满一次）——于是 "qps=10" 实际每秒只放 5 个。
 * 那个 2 是固定窗口时代"键的 TTL"的遗留值，换成令牌桶之后没人改它，
 * 配置项名与行为就这么分家了，而且**没有任何东西会提示**。
 *
 * <p>本类用一个**记账用的假限流器**（记下每次调用的 limit 与 window）来钉住这条语义：
 * 把 {@code (limit, window)} 折算成"每秒多少个"，它**必须等于配置的 qps**。
 * 这样写的好处是断言的是**速率**（行为），而不是"窗口字面量等于 1 秒"（实现细节）——
 * 将来若换成别的计数实现，只要速率对，用例仍然成立。
 */
class RedisIpExactCheckerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GovernanceMetrics metrics = new GovernanceMetrics(meterRegistry);

    /** 记下每次调用参数的假限流器：**不碰 Redis**，所以用例是纯内存的、毫秒级 */
    private static final class RecordingLimiter implements RateLimiter {

        private final List<Call> calls = new ArrayList<>();
        private boolean allow = true;
        private RuntimeException failWith;

        @Override
        public boolean tryAcquire(String subject, int limit, Duration window) {
            calls.add(new Call(subject, limit, window));
            if (failWith != null) {
                throw failWith;
            }
            return allow;
        }

        @Override
        public String algorithm() {
            return "recording";
        }

        private record Call(String subject, int limit, Duration window) {
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("速率必须等于配置的 qps：把 (limit, window) 折成「每秒多少个」，不能打折")
    void rateMatchesConfiguredQps() throws Exception {
        RecordingLimiter limiter = new RecordingLimiter();
        RedisIpExactChecker checker = new RedisIpExactChecker(limiter, metrics, true, 10);

        checker.check("10.1.1.1");

        assertThat(limiter.calls).as("启用时必须真的去判一次").hasSize(1);
        RecordingLimiter.Call call = limiter.calls.get(0);
        assertThat(call.subject())
                .as("键沿用迁移前的前缀与主体，运维的排查习惯不因换实现而失效")
                .isEqualTo("c:rl:ip:10.1.1.1");
        assertThat(call.limit() * 1000.0 / call.window().toMillis())
                .as("配置项叫 qps（每秒上限），折算出来的速率就必须是 10/秒——"
                        + "迁移前这里是 5/秒（窗口 2 秒），且没有任何东西会提示")
                .isEqualTo(10.0);
    }

    @Test
    @DisplayName("未启用（enabled=false）或 qps<=0：直接放行，连限流器都不调用")
    void disabledDoesNotTouchLimiter() throws Exception {
        RecordingLimiter off = new RecordingLimiter();
        new RedisIpExactChecker(off, metrics, false, 10).check("10.1.1.2");

        RecordingLimiter zero = new RecordingLimiter();
        new RedisIpExactChecker(zero, metrics, true, 0).check("10.1.1.3");

        assertThat(off.calls).as("关掉的维度不该产生任何 Redis 往返").isEmpty();
        assertThat(zero.calls).as("0 = 不限（本项目同一语义）").isEmpty();
    }

    @Test
    @DisplayName("超限 → 抛 BlockException 的子类（抛业务异常会被 Sentinel 当成 slot 内部错误吞掉并放行）")
    void overLimit_throwsBlockException() {
        RecordingLimiter limiter = new RecordingLimiter();
        limiter.allow = false;
        RedisIpExactChecker checker = new RedisIpExactChecker(limiter, metrics, true, 10);

        assertThatThrownBy(() -> checker.check("10.1.1.4"))
                .isInstanceOf(BlockException.class)
                .isInstanceOf(IpExactBlockException.class);
    }

    @Test
    @DisplayName("Redis 不可用 → 放行（fail-open），且留下 failopen 指标")
    void redisDown_failsOpenAndIsVisible() {
        RecordingLimiter limiter = new RecordingLimiter();
        limiter.failWith = new org.springframework.data.redis.RedisConnectionFailureException("模拟 Redis 不可用");
        RedisIpExactChecker checker = new RedisIpExactChecker(limiter, metrics, true, 10);

        assertThatCode(() -> checker.check("10.1.1.5"))
                .as("IP 维度是可用性/公平性防线：Redis 挂了应当放行，而不是把全部来源拒之门外")
                .doesNotThrowAnyException();

        assertThat(meterRegistry.find("coupon_ratelimit_failopen_total")
                .tag("dimension", "ip").counter())
                .as("放行必须可见——否则「防线失效」与「流量本来就小」在监控上完全一样")
                .isNotNull();
        assertThat(meterRegistry.find("coupon_ratelimit_failopen_total")
                .tag("dimension", "ip").counter().count()).isEqualTo(1.0);
    }
}
