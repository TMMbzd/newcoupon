package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.infrastructure.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.Map;
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
 * <p><b>为什么叫"形状用例"</b>：光验证"能被限流"不足以证明两个算法不同——那只需一个算法就够了。
 * 这些用例要钉住的是**各自的行为特征**：令牌桶允许一次性突发到容量上限、且按时间恢复；
 * 滑动窗口在时间维度上是连续的（加权两窗口）。
 *
 * <p><b>为什么不用 TestRestTemplate 也不走 HTTP</b>：限流器的行为与协议无关，
 * 直接对实现类测最省事、最快，也最容易看懂。HTTP 那一层由配额用例守着。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest
class RateLimiterIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /** Spring 按 bean 名注入全部实现——算法换了实现类，这里不用改 */
    @Autowired
    private Map<String, RateLimiter> limiters;

    private RateLimiter of(String algorithm) {
        return limiters.values().stream()
                .filter(l -> algorithm.equals(l.algorithm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有算法为 " + algorithm + " 的限流器：" + limiters.keySet()));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("基本行为：滑动窗口在超过上限后拒绝")
    void slidingWindow_limits() {
        RateLimiter limiter = of("sliding_window");
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
        RateLimiter limiter = of("token_bucket");
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
        RateLimiter limiter = of("token_bucket");
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
    @DisplayName("算法标识与配置取值一一对应（调度方靠它挑实现）")
    void algorithms_areAddressable() {
        assertThat(of("fixed_window").algorithm()).isEqualTo("fixed_window");
        assertThat(of("sliding_window").algorithm()).isEqualTo("sliding_window");
        assertThat(of("token_bucket").algorithm()).isEqualTo("token_bucket");
    }
}
