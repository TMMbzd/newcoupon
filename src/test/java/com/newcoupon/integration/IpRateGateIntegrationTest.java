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

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

    private String freshIp() {
        return "10.0." + (IP_SEQ.incrementAndGet() % 250) + ".7";
    }

    @Test
    @Timeout(60)
    @DisplayName("阈值内放行、超阈拒绝、窗口翻转恢复")
    void allowsWithinLimitBlocksOverLimitAndRecovers() {
        String ip = freshIp();

        assertThat(gate.allow(ip)).as("第1次放行").isTrue();
        assertThat(gate.allow(ip)).as("第2次放行").isTrue();
        assertThat(gate.allow(ip)).as("第3次放行（=阈值）").isTrue();

        // 确定性拒绝断言：预填"当前+下一"两个窗口的计数到阈值——消除断言与窗口边界的赛跑
        //（直接写 Redis 而不是多调几次 allow：多调恰跨边界时会落新窗口，纯时序竞态）
        long ws = System.currentTimeMillis() / 2000L;
        redis.opsForValue().set("c:risk:ip:" + ip + ":" + ws, "3");
        redis.opsForValue().set("c:risk:ip:" + ip + ":" + (ws + 1), "3");
        assertThat(gate.allow(ip)).as("第4次拒绝——脚本高频形态被挡在业务逻辑之前").isFalse();
        assertThat(gate.allow(ip)).as("继续拒绝").isFalse();

        // 恢复：清计数后放行（窗口翻转的语义=惩罚有时限，非永久拉黑）
        redis.delete("c:risk:ip:" + ip + ":" + ws);
        redis.delete("c:risk:ip:" + ip + ":" + (ws + 1));
        assertThat(gate.allow(ip)).as("计数清除后恢复放行").isTrue();
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
    @DisplayName("键带 TTL（无残留；窗口键自然过期）")
    void keysHaveTtl() {
        String ip = freshIp();
        gate.allow(ip);
        Long ttl = redis.getExpire(
                java.util.Objects.requireNonNull(redis.keys("c:risk:ip:" + ip + ":*")).iterator().next());
        assertThat(ttl).as("窗口键设置了过期（2倍窗口=4s 内）").isBetween(1L, 4L);
    }

    @Test
    @Timeout(60)
    @DisplayName("Redis 不可达时 fail-open（可用性防线不误杀全量——资损另有独立防线）")
    void failOpenWhenRedisDown() {
        IpRateGate brokenGate = new IpRateGate(null, true, 60, 30);   // redis=null → NPE → catch → 放行
        assertThat(brokenGate.allow("1.2.3.4")).as("Redis 故障时放行：频控失效好过全站拒绝").isTrue();
    }
}
