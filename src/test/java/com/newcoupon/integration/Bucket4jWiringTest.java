package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Bucket4j 与现有 Lettuce 连接的**接线验证**（2026-10-08）。
 *
 * <p><b>为什么先写这条</b>：换用 Bucket4j 的最大技术风险不是算法，而是**能不能复用它**——
 * 项目已经有一整套 Spring Data Redis / Lettuce 的连接（`StringRedisTemplate` 遍布各处），
 * 如果 Bucket4j 必须自建第二个 Redis 客户端栈，那换库的代价就不只是删 265 行代码了
 * （Redsisson 就是因此被排除的：12MB 依赖 + 双客户端栈）。
 *
 * <p>调研给的线索是"`LettuceConnectionFactory.getRequiredNativeClient()` 自 spring-data-redis 2.5
 * 起可用，拿到 `AbstractRedisClient` 后自建 `StatefulRedisConnection`"，
 * 并明确标注**此接线细节需实施时验证**。这条用例就是那个验证。
 *
 * <p>验证通过之后，接线方式会被封进一个配置类，业务代码只面对
 * {@code ProxyManager<String>}（Bucket4j 的抽象），看不到 Lettuce 的存在。
 */
class Bucket4jWiringTest extends ShardedIntegrationTestSupport {

    @Autowired
    private org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory;

    @Test
    @DisplayName("接线验证：从 Spring 的 Lettuce 连接工厂拿到原生客户端，建出 Bucket4j 的 ProxyManager")
    void canReuseSpringLettuceConnectionForBucket4j() {
        // ① Spring 的连接工厂 → 原生 Lettuce 客户端（单机是 RedisClient，集群是 RedisClusterClient）
        assertThat(connectionFactory)
                .as("本项目的 Redis 客户端应当就是 Lettuce（pom 里没有第二个客户端）")
                .isInstanceOf(LettuceConnectionFactory.class);

        AbstractRedisClient nativeClient = ((LettuceConnectionFactory) connectionFactory).getRequiredNativeClient();
        assertThat(nativeClient).isNotNull();

        // ② 用**项目自己的 codec 约定**建连接：键用 String，值用 byte[]
        //    （Bucket4j 的桶状态是序列化后的二进制，不能当字符串）
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

        // 本项目是单机 Redis（compose 里一个容器）；集群形态下 RedisClusterClient 的连接类型不同，
        // Bucket4j 另有 Cluster 用法——这里只验证单机，集群留到真需要时再说。
        StatefulRedisConnection<String, byte[]> connection =
                ((RedisClient) nativeClient).connect(codec);

        try {
            // ③ 建 ProxyManager：CAS 模式（并发安全靠 Redis 端 Lua 里的比较并交换）
            ProxyManager<String> proxyManager = LettuceBasedProxyManager.builderFor(connection)
                    // 必须配：否则不再活跃的租户的桶状态会永远留在 Redis 里
                    .withExpirationStrategy(
                            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                    .build();

            assertThat(proxyManager).isNotNull();

            // ④ 端到端跑一次：拿桶 → 取令牌
            var bucket = proxyManager.builder()
                    .build("wiring-test:" + System.nanoTime(),
                            () -> io.github.bucket4j.BucketConfiguration.builder()
                                    .addLimit(limit -> limit.capacity(5)
                                            .refillGreedy(5, Duration.ofMinutes(1)))
                                    .build());

            assertThat(bucket.tryConsume(1))
                    .as("接线通了：能建桶、能取令牌").isTrue();

            // ⑤ 形状验证：容量 5，取掉 1 个后还剩 4 个
            assertThat(bucket.getAvailableTokens())
                    .as("桶状态确实在 Redis 里，且能读回剩余量（不是每次都新建）")
                    .isEqualTo(4L);
        } finally {
            // 这条连接是测试自己建的，要自己关——生产代码里由配置类统一管理生命周期
            connection.close();
        }
    }

    @Test
    @DisplayName("对照：同一个 subject 跨调用共享状态（证明桶状态真的在 Redis，而不是进程内）")
    void sameSubjectSharesStateViaRedis() {
        AbstractRedisClient nativeClient = ((LettuceConnectionFactory) connectionFactory).getRequiredNativeClient();
        StatefulRedisConnection<String, byte[]> connection =
                ((RedisClient) nativeClient).connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        try {
            ProxyManager<String> pm = LettuceBasedProxyManager.builderFor(connection)
                    .withExpirationStrategy(
                            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                    .build();

            String subject = "wiring-shared:" + System.nanoTime();
            var config = io.github.bucket4j.BucketConfiguration.builder()
                    .addLimit(limit -> limit.capacity(3).refillGreedy(3, Duration.ofHours(1)))
                    .build();

            // 每次 getProxy 都重新拿桶——如果状态在进程内，这里会各算各的
            for (int i = 1; i <= 3; i++) {
                assertThat(pm.builder().build(subject, () -> config).tryConsume(1))
                        .as("第 %d 次应当成功（容量 3）", i).isTrue();
            }
            assertThat(pm.builder().build(subject, () -> config).tryConsume(1))
                    .as("第 4 次必须被拒——状态确实存在 Redis 里，跨调用共享")
                    .isFalse();

            // 这也顺带证明了 key 的语义：**subject 就是"限谁"，不含任何时间信息**
        } finally {
            connection.close();
        }
    }
}
