package com.newcoupon.learning.ratelimit;

import com.newcoupon.infrastructure.ratelimit.RateLimiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 冷启动预热的阈值曲线（S2 第 5 步的能力，2026-10-05 补上可控覆盖）。
 *
 * <p><b>为什么要单独有这个类</b>：预热原本只有一条集成用例覆盖，而它<b>天生测不准</b>——
 * 有效阈值 = {@code limit × (1/3 + 2/3 × 已过时长 / 预热时长)}，其中"已过时长"是
 * <b>JVM 启动至今</b>。在集成测试里，"JVM 已经跑了多久"取决于这个测试类在套件里排第几，
 * 用例控制不了；更糟的是当 JVM 运行时长超过配置的预热时长时，预热<b>早就结束了</b>，
 * 那条用例实际测的是"普通限流"而不是预热。
 *
 * <p>这正是本项目测试铁律③说的"不要用不可控的量卡边界"。修法不是把集成用例调松，
 * 而是<b>把曲线交给可控的单元测试</b>：构造器接受 {@code startMs}，
 * 于是"预热进行到哪一步"变成可以直接给定的输入（把 startMs 设成"现在 − 已过时长"）。
 *
 * <p>集成层的职责相应收窄为"配置接得上、限流没被破坏"，见
 * {@code OpenApiQuotaIntegrationTest#warmUp_throttlesDuringColdStart}。
 */
class WarmUpRateLimiterTest {

    /** 全额阈值——预热只压低它，不改变它的含义 */
    private static final int LIMIT = 30;
    private static final long TEN_MINUTES = 600_000L;

    /** 用假时钟：把 startMs 设成"现在 − 已过时长"，就得到一个确定的预热进度 */
    private WarmUpRateLimiter at(long elapsedMs, long warmupMs) {
        return new WarmUpRateLimiter(new RecordingLimiter(),
                System.currentTimeMillis() - elapsedMs, warmupMs);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("冷启动：阈值压到 1/3（保护还没热身的进程）")
    void atColdStart_limitIsOneThird() {
        assertThat(at(0, TEN_MINUTES).effectiveLimit(LIMIT))
                .as("30 × 1/3 = 10——刚启动时只放 1/3 的流量通过")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("中段：阈值线性抬升（半程约 2/3）")
    void midway_limitRampsUp() {
        assertThat(at(TEN_MINUTES / 2, TEN_MINUTES).effectiveLimit(LIMIT))
                .as("30 × (1/3 + 2/3 × 0.5) = 20")
                .isEqualTo(20);
    }

    @Test
    @DisplayName("预热结束：回到全额阈值；再往后也不超过它")
    void afterWarmup_fullLimit() {
        assertThat(at(TEN_MINUTES, TEN_MINUTES).effectiveLimit(LIMIT)).isEqualTo(LIMIT);
        assertThat(at(TEN_MINUTES * 10, TEN_MINUTES).effectiveLimit(LIMIT))
                .as("预热是「逐渐放开到全额」，不是「越跑越放开」").isEqualTo(LIMIT);
    }

    @Test
    @DisplayName("时钟回拨按「最冷」处理——时间异常时宁可少放，也不放开全量")
    void clockBackwards_treatedAsColdest() {
        WarmUpRateLimiter limiter = new WarmUpRateLimiter(new RecordingLimiter(),
                System.currentTimeMillis() + 60_000, TEN_MINUTES);   // startMs 在未来 = 时钟回拨

        assertThat(limiter.effectiveLimit(LIMIT))
                .as("时间异常时保守处理——放开全量比少放一点危险得多")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("边界①：不限（limit ≤ 0）不预热——「不限」不该被预热变成「限」")
    void unlimitedNotWarmedUp() {
        assertThat(at(0, TEN_MINUTES).effectiveLimit(0)).isZero();
    }

    @Test
    @DisplayName("边界②：预热时长非正不预热（配置成 0 就是关掉）")
    void nonPositiveWarmupNotApplied() {
        assertThat(at(0, 0).effectiveLimit(LIMIT)).isEqualTo(LIMIT);
    }

    @Test
    @DisplayName("边界③：小阈值不会被压到 0（0 在一些实现里意味着「不限」，方向正好相反）")
    void smallLimitNeverBecomesZero() {
        assertThat(at(0, TEN_MINUTES).effectiveLimit(1))
                .as("1/3 取整是 0，但必须兜到 1——0 会被下游解读成「不限」").isEqualTo(1);
        assertThat(at(0, TEN_MINUTES).effectiveLimit(2)).isEqualTo(1);
    }

    @Test
    @DisplayName("装饰器把**有效阈值**传下去（不是把原始阈值传下去、只在本地算算）")
    void tryAcquirePassesEffectiveLimitDownstream() {
        RecordingLimiter delegate = new RecordingLimiter();
        WarmUpRateLimiter limiter = new WarmUpRateLimiter(delegate,
                System.currentTimeMillis(), TEN_MINUTES);

        limiter.tryAcquire("k", LIMIT, Duration.ofSeconds(2));

        assertThat(delegate.lastLimit)
                .as("这一条守的是「预热真的生效」——只算不传等于没做")
                .isEqualTo(10);
    }

    /** 记录收到的阈值——用来证明"传下去的是有效阈值" */
    private static final class RecordingLimiter implements RateLimiter {
        private int lastLimit = -1;

        @Override
        public boolean tryAcquire(String key, int limit, Duration window) {
            this.lastLimit = limit;
            return true;
        }

        @Override
        public String algorithm() {
            return "recording";
        }
    }
}
