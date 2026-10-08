package com.newcoupon.infrastructure.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import com.newcoupon.infrastructure.ratelimit.RateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 风控 IP 频控闸门（第 6.5 步收敛，2026-10-08）。
 *
 * <p><b>改了三件事</b>（都是"同一个问题在别处已经解决过、这里还留着旧做法"）：
 * <ol>
 *   <li>计数从**手写 INCR/EXPIRE 固定窗口**换成项目统一的 {@link RateLimiter}
 *       （令牌桶）——那是项目里最后一处重复造轮子的限流实现；</li>
 *   <li>fail-open 补上**日志与指标**：原先 {@code catch (RuntimeException e) { return true; }}
 *       连异常对象都丢掉了，于是"风控闸失效"与"今天没人刷"在监控上完全一样；</li>
 *   <li>拒绝响应补 {@code Retry-After}（在过滤器那侧，见 {@code RiskControlFilterTest}）。</li>
 * </ol>
 *
 * <p>用**记账用的假限流器**：不碰 Redis，毫秒级，而且能钉住"传了什么参数"——
 * 键要去掉窗口号（subject 只表示"限谁"），窗口要等于配置的秒数。
 */
class IpRateGateTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GovernanceMetrics metrics = new GovernanceMetrics(meterRegistry);

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
    @DisplayName("计数交给统一的限流器：键只表示「限谁」，窗口由配置给")
    void delegatesToSharedLimiter() {
        RecordingLimiter limiter = new RecordingLimiter();
        IpRateGate gate = new IpRateGate(limiter, metrics, true, 60, 30);

        assertThat(gate.allow("1.2.3.4")).isTrue();

        assertThat(limiter.calls).as("必须真的走统一限流器（原来是自己手写 INCR/EXPIRE）").hasSize(1);
        RecordingLimiter.Call call = limiter.calls.get(0);
        assertThat(call.subject())
                .as("键沿用既有前缀，但**不再拼窗口号**——窗口是实现的私有职责，"
                        + "调用方拼了它，滑动窗口/令牌桶就读不到自己需要的历史")
                .isEqualTo("c:risk:ip:1.2.3.4");
        assertThat(call.limit()).isEqualTo(30);
        assertThat(call.window()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("超阈拒绝：限流器说不放行，闸门就说不放行")
    void overLimitRejects() {
        RecordingLimiter limiter = new RecordingLimiter();
        limiter.allow = false;
        IpRateGate gate = new IpRateGate(limiter, metrics, true, 60, 30);

        assertThat(gate.allow("1.2.3.4")).isFalse();
    }

    @Test
    @DisplayName("未启用：直接放行，连限流器都不调用（关掉的闸门不该产生 Redis 往返）")
    void disabledDoesNotTouchLimiter() {
        RecordingLimiter limiter = new RecordingLimiter();
        IpRateGate gate = new IpRateGate(limiter, metrics, false, 60, 30);

        assertThat(gate.allow("1.2.3.4")).isTrue();
        assertThat(limiter.calls).isEmpty();
    }

    @Test
    @DisplayName("Redis 故障 → 放行（fail-open），且**必须留下指标与日志**")
    void redisDownFailsOpenAndIsVisible() {
        RecordingLimiter limiter = new RecordingLimiter();
        limiter.failWith = new RedisConnectionFailureException("模拟 Redis 不可用");
        IpRateGate gate = new IpRateGate(limiter, metrics, true, 60, 30);

        assertThat(gate.allow("1.2.3.4"))
                .as("频控是可用性防线不是资损防线：Redis 挂了放行，而不是拒掉全部领券")
                .isTrue();

        assertThat(meterRegistry.find("coupon_ratelimit_failopen_total")
                .tag("dimension", "risk_ip").counter())
                .as("**放行必须可见**：没有这条指标，「风控闸已经失效」与「今天没人刷」在监控上完全一样。"
                        + "（改之前这里连异常都丢掉了，日志里也什么都没有）")
                .isNotNull();
        assertThat(meterRegistry.find("coupon_ratelimit_failopen_total")
                .tag("dimension", "risk_ip").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Retry-After = 令牌补充周期（窗口/容量），且下限 1 秒")
    void retryAfterIsRefillInterval() {
        assertThat(new IpRateGate(new RecordingLimiter(), metrics, true, 60, 30).retryAfterSeconds())
                .as("每 60 秒补 30 个 → 一个令牌 2 秒").isEqualTo(2);
        assertThat(new IpRateGate(new RecordingLimiter(), metrics, true, 1, 100).retryAfterSeconds())
                .as("整除结果为 0 时必须钳到 1：Retry-After: 0 等于让客户端立刻重试（把限流变成自我加压）")
                .isEqualTo(1);
    }
}
