package com.newcoupon.infrastructure.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Bucket4j 与现有 Redis 连接的接线（2026-10-08）。
 *
 * <p><b>这一层的唯一职责</b>：把 Bucket4j 需要的 {@code StatefulRedisConnection} 从
 * **项目已有的** Lettuce 连接工厂里取出来，别引入第二个 Redis 客户端栈。
 * 业务代码只面对 {@link ProxyManager}，看不到 Lettuce 的存在。
 *
 * <p><b>为什么复用而不是自建</b>：项目里 {@code StringRedisTemplate} 遍布各处
 * （库存闸门、对账、锁、租户配置……）。如果 Bucket4j 自带一套客户端，
 * 同一个进程里就会有两条 Redis 连接池、两套超时配置、两份监控——
 * 这正是调研里排除 Redisson 的主要理由（它 12MB 依赖、且必须自带客户端）。
 *
 * <p><b>接线方式经实测确认</b>（见 {@code Bucket4jWiringTest}）：
 * {@code LettuceConnectionFactory.getRequiredNativeClient()} 拿到 {@link AbstractRedisClient}，
 * 再用**项目自己的 codec 约定**（键 String、值 byte[]——桶状态是序列化后的二进制，
 * 不能当字符串）建连接。
 *
 * <p><b>两处容易踩的坑</b>：
 * <ol>
 *   <li><b>必须配 {@code ExpirationAfterWriteStrategy}</b>：桶状态写在 Redis 里，
 *       不设过期的话，一个只被访问过一次的租户的桶会**永远留着**——
 *       租户数增长时这是稳定的内存泄漏。</li>
 *   <li><b>坐标与包名不一致</b>：Maven 坐标是 {@code com.bucket4j:bucket4j_jdk17-*}，
 *       而 Java 包名是 {@code io.github.bucket4j.*}。照官网 groupId 写 import 会编译失败。</li>
 * </ol>
 */
@Configuration
public class Bucket4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Bucket4jConfig.class);

    /** 桶状态在"最后一次写入后"保留多久——取值依据见 {@link #proxyManager} */
    private static final Duration STATE_TTL_AFTER_LAST_WRITE = Duration.ofMinutes(10);

    private StatefulRedisConnection<String, byte[]> connection;

    /**
     * 桶的代理管理器：Bucket4j 通过它把"某个主体的桶"映射到 Redis 里的一个键。
     *
     * <p>用 CAS 模式（{@code casBasedBuilder}）：并发安全靠 Redis 端脚本里的
     * "读取-比较-写回"原子完成，不需要额外的锁。
     *
     * <p>{@code ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax} 的语义是
     * "这个桶最多需要多久能重新填满，就留多久"——对"容量 N、每 M 分钟补满"的桶，
     * 它算出的保留时间与桶的补充速度匹配：活跃租户的桶一直有人写所以一直续期，
     * 不再被访问的桶会在填满所需的时间之后被回收。上界由 {@link #STATE_TTL_AFTER_LAST_WRITE} 兜住。
     */
    @Bean
    public ProxyManager<String> bucket4jProxyManager(
            org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuceFactory)) {
            // 本项目一直用 Lettuce（pom 里没有第二个客户端）。换客户端的话这里要跟着改，
            // 所以宁可启动就失败，也不要静默地换一条没人验证过的路径。
            throw new IllegalStateException(
                    "Bucket4j 的接线依赖 Lettuce，当前连接工厂是：" + connectionFactory.getClass());
        }

        AbstractRedisClient nativeClient = lettuceFactory.getRequiredNativeClient();
        if (!(nativeClient instanceof RedisClient client)) {
            // 单机 Redis（compose 里一个容器）。集群形态的连接类型不同，Bucket4j 另有 Cluster 用法，
            // 真需要时再补——现在明确失败，好过悄悄按单机处理。
            throw new IllegalStateException("当前只接线了单机 Redis，实际客户端类型：" + nativeClient.getClass());
        }

        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        this.connection = client.connect(codec);

        // 用 Bucket4jLettuce 这个入口（旧写法 LettuceBasedProxyManager.builderFor 在 8.21 已废弃）
        ProxyManager<String> proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(STATE_TTL_AFTER_LAST_WRITE))
                .build();

        log.info("Bucket4j 已接线：复用现有 Lettuce 连接，桶状态存 Redis（CAS 模式）");
        return proxyManager;
    }

    /**
     * 关闭连接。
     *
     * <p>这条连接是**我们自己建的**（不是 Spring 管的那个），所以也要自己关——
     * 否则应用关闭时它会留在那里，测试里则表现为每次上下文刷新都漏一条连接。
     */
    @PreDestroy
    void closeConnection() {
        if (connection != null) {
            connection.close();
            log.debug("Bucket4j 的 Redis 连接已关闭");
        }
    }
}
