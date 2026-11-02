package com.newcoupon.infrastructure.redis;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 缓存失效监听容器装配（与 {@link CacheInvalidationBus} 配套）。
 *
 * <p>用**精确频道**订阅而不是 pattern：Redis Cluster 下 pattern 订阅只落在单个节点，
 * 而普通 PUBLISH 会广播到所有节点，精确频道才能保证每个实例都收到。
 *
 * <p>{@code coupon.cache.invalidation.enabled=false} 时整个容器不装配——排查
 * "pub/sub 是否干扰业务"时可按开关退回纯本地失效 + TTL。
 */
@Configuration
@ConditionalOnProperty(name = "coupon.cache.invalidation.enabled", havingValue = "true", matchIfMissing = true)
public class CacheInvalidationConfig {

    @Bean(destroyMethod = "destroy")
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationBus bus,
            @Value("${coupon.cache.invalidation.channel:c:cache:invalidate}") String channel) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 订阅连接断开后的重连间隔；即使长时间断开，版本键轮询仍会收敛。
        container.setRecoveryInterval(5_000L);
        container.addMessageListener(
                (message, pattern) -> bus.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(channel));
        return container;
    }
}
