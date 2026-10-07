package com.newcoupon.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 日配额的 Redis 实现（迁移第 6 步，2026-10-08）——<b>红绿灯</b>。
 *
 * <p><b>红</b>：迁移到 Sentinel 的处理链之后，本类是在 {@code ProcessorSlot} 里被调用的。
 * 它直接抛出的 Redis 异常会被 {@code CtSph} 的 {@code catch (Throwable)} 当成
 * "slot 自己的内部错误"**吞掉并放行**——行为上确实是 fail-open（放行），
 * **但指标没了**：迁移前那条 {@code coupon_ratelimit_failopen_total{dimension="tenant_daily"}}
 * 是过滤器自己打的，现在没有任何人打。
 *
 * <p>后果正是 {@code GovernanceMetrics.recordLimitFailOpen} 的注释里写的那个假象：
 * **「Redis 挂了、日配额形同虚设」与「流量本来就小」在监控上完全一样**。
 * 所以本类的第一条用例先红在这里。
 *
 * <p>用 mock 而不是真去连一个坏掉的 Redis：故障类型是刻意选成
 * {@link RedisConnectionFailureException} 的——那是 Spring Data Redis 把
 * "连不上/读超时"翻译出来的真实类型（S2 第 10 步核实过，它的类型也在熔断器的
 * "依赖故障"白名单里，两处对"什么算依赖故障"的判断是一致的）。
 */
class RedisDailyQuotaCheckerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GovernanceMetrics metrics = new GovernanceMetrics(meterRegistry);

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Redis 不可用 → 放行（fail-open），且留下 failopen 指标——否则「防线失效」在监控上不可见")
    void redisDown_failsOpenAndIsVisible() {
        RedisDailyQuotaChecker checker = newChecker(redisThatFails(), 1);

        assertThatCode(() -> checker.check("t-daily-failopen"))
                .as("日配额是可用性/公平性防线，不是资损防线：Redis 挂了应当放行，而不是拒绝全部接入方")
                .doesNotThrowAnyException();

        Counter counter = meterRegistry.find("coupon_ratelimit_failopen_total")
                .tag("dimension", "tenant_daily")
                .counter();
        assertThat(counter)
                .as("指标**必须**留下：没有它，「日配额已经形同虚设」与「流量本来就小」在监控上完全一样。"
                        + "（Micrometer 的 Counter 在首次自增前不产生时间序列，所以 null 就等于「没打过点」）")
                .isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("计数超过上限 → 抛 DailyQuotaBlockException（BlockException 子类），且 Retry-After 是到明天的秒数")
    void overLimit_throwsBlockException() {
        RedisDailyQuotaChecker checker = newChecker(redisReturning(2L), 1);

        assertThatThrownBy(() -> checker.check("t-daily-over"))
                .as("超限是**拒绝**，不是故障——必须抛出去让 Sentinel 的出口写 429，"
                        + "而且必须是 BlockException 的子类（否则会被静默吞掉并放行）")
                .isInstanceOf(DailyQuotaBlockException.class)
                .satisfies(e -> assertThat(((DailyQuotaBlockException) e).retryAfterSeconds())
                        .as("Retry-After 要答「到明天还有多少秒」，接入方才不用猜")
                        .isBetween(1L, 86400L));

        assertThat(meterRegistry.find("coupon_ratelimit_failopen_total")
                .tag("dimension", "tenant_daily").counter())
                .as("拒绝**不是**故障：它绝不能把 failopen 指标打起来，否则运维会去查一个不存在的依赖故障")
                .isNull();
    }

    @Test
    @DisplayName("上限 <= 0 = 不限：连 Redis 都不碰（不能因为「不限」还去多一次往返）")
    void unlimited_doesNotTouchRedis() {
        StringRedisTemplate redis = redisReturning(1L);
        RedisDailyQuotaChecker checker = newChecker(redis, 0);

        assertThatCode(() -> checker.check("t-daily-unlimited")).doesNotThrowAnyException();

        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("第一次计数才设过期：每次请求都 EXPIRE 会把窗口一直续命，窗口就永不结束")
    void expireOnlyOnFirstCount() {
        StringRedisTemplate redis = redisReturning(1L);
        RedisDailyQuotaChecker checker = newChecker(redis, 100);

        assertThatCode(() -> checker.check("t-daily-expire")).doesNotThrowAnyException();

        verify(redis).expire(anyString(), any(java.time.Duration.class));
    }

    // ------------------------------------------------------------------

    private RedisDailyQuotaChecker newChecker(StringRedisTemplate redis, int limit) {
        TenantConfigService config = mock(TenantConfigService.class);
        when(config.get(anyString(), any())).thenReturn(String.valueOf(limit));
        when(config.getInt(anyString(), any())).thenReturn(limit);
        return new RedisDailyQuotaChecker(redis, config, mock(OpenAppRepository.class), metrics);
    }

    /** 计数正常递增的 Redis（返回固定值，够用即可——本类的重点是故障路径） */
    @SuppressWarnings("unchecked")
    private StringRedisTemplate redisReturning(long count) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(count);
        return redis;
    }

    /** 与真实故障同型：连不上 Redis 时 Spring Data Redis 翻译出来的就是它 */
    @SuppressWarnings("unchecked")
    private StringRedisTemplate redisThatFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("模拟 Redis 不可用"));
        return redis;
    }
}
