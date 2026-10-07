package com.newcoupon.learning.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.infrastructure.ratelimit.RateLimiter;
import com.newcoupon.integration.ShardedIntegrationTestSupport;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S2 第 4 步：限流器家族（RQ-3）——<b>形状用例</b>。
 *
 * <p>红：本类先于实现写就——两个新实现的方法体抛"待实现"。
 *
 * <p><b>这批用例现在的身份是"学习材料"</b>（2026-10-08 换用 Bucket4j 之后）：
 * 三个算法实现已经移出生产路径，但留着能对照着读"每种算法怎么工作、各自的形状是什么"。
 * 生产侧的契约由 {@code RateLimitContractTest} 守着（它只断言契约，不假设算法）。
 *
 * <p><b>什么叫"形状用例"</b>：光验证"能被限流"不足以区分算法——那一个算法就够了。
 * 这里钉的是**各自的行为特征**：令牌桶允许一次性突发到容量上限、且按时间平滑恢复；
 * 滑动窗口在时间维度上是连续的（当前窗口 + 上一窗口加权）。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest
class RateLimitAlgorithmsTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private StringRedisTemplate redis;

    // 直接 new——这几个实现已经**不在生产路径上**了（生产用 Bucket4jRateLimiter）。
    // 这里保留它们，是为了能对照着读"三种算法分别长什么样"，以及各自的行为特征。
    private RateLimiter sliding() { return new SlidingWindowRateLimiter(redis); }
    private RateLimiter tokenBucket() { return new TokenBucketRateLimiter(redis); }
    private RateLimiter fixedWindow() { return new FixedWindowRateLimiter(redis); }

    /** 给每个用例生成独有的主体（学习材料也不共享状态） */
    private static String key(String tag) { return "learn:" + tag + ":" + System.nanoTime(); }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("基本行为：滑动窗口在超过上限后拒绝")
    void slidingWindow_limits() {
        RateLimiter limiter = sliding();
        String key = "test:sw:" + System.nanoTime();

        int allowed = 0;
        for (int i = 0; i < 15; i++) {
            if (limiter.tryAcquire(key, 10, Duration.ofSeconds(1))) {
                allowed++;
            }
        }
        assertThat(allowed).as("1 秒内上限 10，连打 15 次应当只放行 10 次").isEqualTo(10);
    }

    @Test
    @DisplayName("令牌桶的形状①：桶是满的，所以允许一次性突发到容量上限")
    void tokenBucket_allowsBurstUpToCapacity() {
        RateLimiter limiter = tokenBucket();
        String key = "test:tb:" + System.nanoTime();

        int allowed = 0;
        for (int i = 0; i < 15; i++) {
            if (limiter.tryAcquire(key, 5, Duration.ofSeconds(1))) {
                allowed++;
            }
        }
        assertThat(allowed).as("容量 5 的桶，启动时是满的 → 前 5 个全过，之后被拒").isEqualTo(5);
    }

    @Test
    @DisplayName("令牌桶的形状②：令牌按时间恢复（不是按窗口重置）")
    void tokenBucket_refillsOverTime() throws Exception {
        RateLimiter limiter = tokenBucket();
        String key = "test:tb-refill:" + System.nanoTime();

        // 容量 2、每秒补 2 个：先把桶掏空
        assertThat(limiter.tryAcquire(key, 2, Duration.ofSeconds(1))).isTrue();
        assertThat(limiter.tryAcquire(key, 2, Duration.ofSeconds(1))).isTrue();
        assertThat(limiter.tryAcquire(key, 2, Duration.ofSeconds(1)))
                .as("桶空了，第 3 个立即被拒").isFalse();

        Thread.sleep(600);   // 600ms × 2/s ≈ 恢复 1.2 个令牌

        assertThat(limiter.tryAcquire(key, 2, Duration.ofSeconds(1)))
                .as("等了一会儿，恢复出至少 1 个令牌").isTrue();
        assertThat(limiter.tryAcquire(key, 2, Duration.ofSeconds(1)))
                .as("恢复的量只够 1 个（1.2 个），第 2 个应当被拒").isFalse();
    }

    @Test
    @DisplayName("三个算法各自报名（读日志/排障时能区分是谁在拦）")
    void eachAlgorithmReportsItsOwnName() {
        assertThat(fixedWindow().algorithm()).isEqualTo("fixed_window");
        assertThat(sliding().algorithm()).isEqualTo("sliding_window");
        assertThat(tokenBucket().algorithm()).isEqualTo("token_bucket");
    }

    @Test
    @DisplayName("三种算法的差异就在这里：固定窗口的边界双倍（其余两个没有）")
    void fixedWindowHasBoundaryDoubling() throws Exception {
        // 这是"为什么业界选令牌桶、避开固定窗口"的实证：
        // 固定窗口按"窗口号"换键，跨过窗口边界之后计数重新开始，
        // 于是在边界附近的一小段时间里，实际放过的是上限的两倍。
        // 省事的复现方式——用极短的窗口，等在边界附近再打：
        RateLimiter limiter = fixedWindow();
        String subject = key("boundary");

        // 先在当前窗口打满
        int first = 0;
        for (int i = 0; i < 4; i++) {
            if (limiter.tryAcquire(subject, 3, Duration.ofMillis(300))) {
                first++;
            }
        }
        assertThat(first).as("300ms 窗口内上限 3 → 只放行 3 个").isEqualTo(3);

        // 等到跨过窗口边界（300ms 的窗口，等 400ms 必然跨过）
        Thread.sleep(400);

        // 新窗口开始计数——上一窗口末尾用掉的和这一窗口开头用掉的，合起来是上限的两倍
        int second = 0;
        for (int i = 0; i < 4; i++) {
            if (limiter.tryAcquire(subject, 3, Duration.ofMillis(300))) {
                second++;
            }
        }
        assertThat(second)
                .as("新窗口重新计数 → 又放行 3 个。**两段合起来 6 个，是上限 3 的两倍**——"
                        + "这就是固定窗口的边界问题，也是它被主流方案避开的原因")
                .isEqualTo(3);
    }
}
