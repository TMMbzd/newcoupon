package com.newcoupon.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis 运行数据采集（企业 Redis 维护的基本盘）。
 *
 * <p>只采集低基数、可行动的指标：内存/碎片、淘汰键、过期键、命中率、连接数、复制状态。
 * 不按 key/template 打标签，避免指标基数爆炸。采集失败只把 up 置 0，不影响业务。
 */
@Component
public class RedisInfoMetrics {

    private static final Logger log = LoggerFactory.getLogger(RedisInfoMetrics.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final AtomicLong up = new AtomicLong(0);
    private final AtomicLong usedMemory = new AtomicLong(0);
    private final AtomicLong maxMemory = new AtomicLong(0);
    private final AtomicLong evictedKeys = new AtomicLong(0);
    private final AtomicLong expiredKeys = new AtomicLong(0);
    private final AtomicLong keyspaceHits = new AtomicLong(0);
    private final AtomicLong keyspaceMisses = new AtomicLong(0);
    private final AtomicLong connectedClients = new AtomicLong(0);
    private final AtomicLong connectedSlaves = new AtomicLong(0);
    private final AtomicLong masterLinkUp = new AtomicLong(0);
    private final AtomicLong roleMaster = new AtomicLong(0);
    private final AtomicReference<Double> fragmentation = new AtomicReference<>(0.0);
    private final AtomicBoolean policyWarned = new AtomicBoolean(false);

    public RedisInfoMetrics(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void register() {
        gauge("newcoupon_redis_info_up", up);
        gauge("newcoupon_redis_used_memory_bytes", usedMemory);
        gauge("newcoupon_redis_maxmemory_bytes", maxMemory);
        gauge("newcoupon_redis_evicted_keys_total", evictedKeys);
        gauge("newcoupon_redis_expired_keys_total", expiredKeys);
        gauge("newcoupon_redis_keyspace_hits_total", keyspaceHits);
        gauge("newcoupon_redis_keyspace_misses_total", keyspaceMisses);
        gauge("newcoupon_redis_connected_clients", connectedClients);
        gauge("newcoupon_redis_connected_slaves", connectedSlaves);
        gauge("newcoupon_redis_master_link_up", masterLinkUp);
        gauge("newcoupon_redis_role_master", roleMaster);
        Gauge.builder("newcoupon_redis_mem_fragmentation_ratio", fragmentation, AtomicReference::get)
                .description("Redis mem_fragmentation_ratio")
                .register(meterRegistry);
    }

    private void gauge(String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get)
                .description(name)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${coupon.redis.info-interval-ms:30000}",
            initialDelayString = "${coupon.redis.info-initial-delay-ms:5000}")
    public void collect() {
        try {
            Properties memory = info("memory");
            Properties stats = info("stats");
            Properties clients = info("clients");
            Properties replication = info("replication");

            usedMemory.set(longValue(memory, "used_memory"));
            maxMemory.set(longValue(memory, "maxmemory"));
            fragmentation.set(doubleValue(memory, "mem_fragmentation_ratio"));
            evictedKeys.set(longValue(stats, "evicted_keys"));
            expiredKeys.set(longValue(stats, "expired_keys"));
            keyspaceHits.set(longValue(stats, "keyspace_hits"));
            keyspaceMisses.set(longValue(stats, "keyspace_misses"));
            connectedClients.set(longValue(clients, "connected_clients"));
            connectedSlaves.set(longValue(replication, "connected_slaves"));
            masterLinkUp.set("down".equalsIgnoreCase(replication.getProperty("master_link_status"))
                    ? 0 : 1);
            roleMaster.set("master".equalsIgnoreCase(replication.getProperty("role")) ? 1 : 0);
            up.set(1);

            String policy = memory.getProperty("maxmemory_policy");
            if (policy != null && (policy.startsWith("allkeys") || policy.startsWith("volatile"))
                    && policyWarned.compareAndSet(false, true)) {
                log.warn("Redis maxmemory-policy={} can evict gate/lock keys; "
                        + "use noeviction or a separate Redis for coordination keys", policy);
            }
        } catch (RuntimeException e) {
            up.set(0);
            log.debug("collect redis INFO failed", e);
        }
    }

    private Properties info(String section) {
        Properties properties = redis.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().info(section));
        return properties == null ? new Properties() : properties;
    }

    private static long longValue(Properties p, String key) {
        try {
            return Long.parseLong(p.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleValue(Properties p, String key) {
        try {
            return Double.parseDouble(p.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
