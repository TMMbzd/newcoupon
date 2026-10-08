package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.infrastructure.risk.IpRateGate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * IP 频控闸门验收（docs/11 §6，2026-09-16）。
 *
 * <p>问题背景：领取入口此前无任何 IP 维度限制——脚本换着 userId 高频领券全通过
 * （库存/限领只防"量"，不防"速"）。期望：阈值内放行、超阈 429 拒绝、窗口翻转恢复、
 * 开关旁路。测试用真 Redis 容器（闸门的行为=Redis 计数语义，mock 无意义）。
 */
@SpringBootTest(properties = {
        "coupon.risk.ip-rate.enabled=true",
        "coupon.risk.ip-rate.window-seconds=2",
        "coupon.risk.ip-rate.max-per-window=3"})
class IpRateGateIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private IpRateGate gate;
    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

    private String freshIp() {
        return "10.0." + (IP_SEQ.incrementAndGet() % 250) + ".7";
    }

    @Test
    @Timeout(60)
    @DisplayName("阈值内放行、超阈拒绝、随补充恢复")
    void allowsWithinLimitBlocksOverLimitAndRecovers() {
        String ip = freshIp();

        assertThat(gate.allow(ip)).as("第1次放行").isTrue();
        assertThat(gate.allow(ip)).as("第2次放行").isTrue();
        assertThat(gate.allow(ip)).as("第3次放行（=容量）").isTrue();
        // **第 6.5 步换了计数实现**：原来是手写固定窗口（键带窗口号，断言要"预填当前+下一窗口"
        // 才能避开与窗口边界的赛跑）；现在是统一的令牌桶（容量 3、每 2 秒补 3 个 = 每 667ms 一个）。
        // 上面三发是连续调用（毫秒级），此刻桶是空的 → 紧接着的第四发必被拒。**与边界无关**。
        assertThat(gate.allow(ip)).as("第4次拒绝——脚本高频形态被挡在业务逻辑之前").isFalse();

        // 恢复：不靠"手动清键"，而是等令牌自己补回来。宽容地轮询到 5 秒上限——
        // "多久恢复"是实现的细节，"最终会恢复"才是契约（同一个写法见 RateLimitContractTest）。
        boolean recovered = false;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (gate.allow(ip)) {
                recovered = true;
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(recovered).as("令牌会补回来——频控是「暂时挡住」，不是「永久拉黑」").isTrue();
    }

    @Test
    @Timeout(60)
    @DisplayName("不同 IP 互不影响（NAT 误伤控制的第一道：按 IP 独立计数）")
    void differentIpsIndependent() {
        String a = freshIp();
        String b = freshIp();
        for (int i = 0; i < 3; i++) {
            gate.allow(a);
        }
        assertThat(gate.allow(b)).as("另一个 IP 不背甲的账").isTrue();
    }

    @Test
    @Timeout(60)
    @DisplayName("桶有 TTL（无残留）")
    void keysHaveTtl() {
        String ip = freshIp();
        gate.allow(ip);

        // 键的**精确形态**由实现（Bucket4j 的分布式桶）决定，所以这里用前缀匹配后再断言
        // "确实是我们的键"——真正要守的是**运维的排查习惯**：`redis-cli --scan 'c:risk:ip:*'`
        // 必须仍然能找到它。（第 6.5 步换实现时，前缀与主体一字未改。）
        var keys = redis.keys("c:risk:ip:" + ip + "*");
        assertThat(keys).as("按既有前缀应当能找到桶键").isNotEmpty();
        String key = keys.iterator().next();
        assertThat(key).startsWith("c:risk:ip:" + ip);
        assertThat(redis.getExpire(key)).as("桶会自然过期，不留残留").isGreaterThan(0L);
    }

    @Test
    @Timeout(60)
    @DisplayName("Redis 不可达时 fail-open，且留下 failopen 指标（防线失效必须可见）")
    void failOpenWhenRedisDown() {
        // 用一个"一用就炸"的限流器代表 Redis 不可达（比 mock 更直白，且异常类型与真实故障同型）
        com.newcoupon.infrastructure.ratelimit.RateLimiter broken =
                new com.newcoupon.infrastructure.ratelimit.RateLimiter() {
                    @Override
                    public boolean tryAcquire(String subject, int limit, java.time.Duration window) {
                        throw new org.springframework.data.redis.RedisConnectionFailureException(
                                "模拟 Redis 不可用");
                    }

                    @Override
                    public String algorithm() {
                        return "broken";
                    }
                };
        IpRateGate brokenGate = new IpRateGate(broken,
                new com.newcoupon.infrastructure.metrics.GovernanceMetrics(meterRegistry), true, 60, 30);

        assertThat(brokenGate.allow("1.2.3.4"))
                .as("Redis 故障时放行：频控失效好过全站拒绝（资损另有库存闸门/DB 双防线）")
                .isTrue();

        assertThat(meterRegistry.find("coupon_ratelimit_failopen_total")
                .tag("dimension", "risk_ip").counter())
                .as("**放行必须可见**：改之前这里连异常都丢掉了，于是「风控闸已失效」"
                        + "与「今天没人刷」在监控上完全一样")
                .isNotNull();
    }
}
