package com.newcoupon.infrastructure.id;

import com.newcoupon.infrastructure.redis.RedisScanLock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/**
 * Snowflake workerId 租约（ADR-0021 Phase 1）。
 *
 * <p>为什么必须做：workerId 相同 = 同一毫秒序列可能撞 ID；默认值 1 在多实例下是重复键/静默跳过事件。
 * 实例启动时在 Redis 抢占 `c:id:worker:{id}`（SET NX + TTL），每 20s 续租，关闭时释放；
 * 1024 个 ID 用尽或显式指定的 ID 已被占用则启动失败，不静默降级。
 *
 * <p>Redis 只是租约协调，不是 ID 权威；ID 唯一性仍由 workerId+时间戳+序列保证。
 */
@Component
public class WorkerIdLease {

    private static final Logger log = LoggerFactory.getLogger(WorkerIdLease.class);
    private static final int MAX_WORKER_ID = 1023;
    private static final Duration LEASE_TTL = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "c:id:worker:";

    private final StringRedisTemplate redis;
    private final RedisScanLock lock;
    private final String owner = UUID.randomUUID().toString();
    private final long workerId;
    private final String key;

    public WorkerIdLease(StringRedisTemplate redis,
                         RedisScanLock lock,
                         @Value("${coupon.id.worker-id:}") String configured) {
        this.redis = redis;
        this.lock = lock;
        this.workerId = allocate(configured);
        this.key = KEY_PREFIX + workerId;
        log.info("snowflake worker lease acquired: workerId={}, owner={}", workerId, owner);
    }

    public long workerId() {
        return workerId;
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 20_000)
    public void renew() {
        try {
            String current = redis.opsForValue().get(key);
            if (!owner.equals(current)) {
                log.error("SNOWFLAKE WORKER LEASE LOST: workerId={}, currentOwner={}",
                        workerId, current);
                return;
            }
            redis.expire(key, LEASE_TTL);
        } catch (RuntimeException e) {
            // 续租失败不立即停发：TTL 内仍持有租约；恢复后下一轮续上。
            log.error("snowflake worker lease renew failed: workerId={}", workerId, e);
        }
    }

    @PreDestroy
    void release() {
        try {
            lock.unlock(key, owner);
        } catch (RuntimeException e) {
            log.warn("snowflake worker lease release failed: workerId={}", workerId, e);
        }
    }

    private long allocate(String configured) {
        if (configured != null && !configured.isBlank()) {
            long requested;
            try {
                requested = Long.parseLong(configured.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("WORKER_ID 不是整数: " + configured, e);
            }
            if (requested < 0 || requested > MAX_WORKER_ID) {
                throw new IllegalStateException("WORKER_ID 必须在 0-" + MAX_WORKER_ID);
            }
            if (!tryAcquire(requested)) {
                throw new IllegalStateException("WORKER_ID=" + requested + " 已被其他实例占用");
            }
            return requested;
        }
        for (int candidate = 0; candidate <= MAX_WORKER_ID; candidate++) {
            if (tryAcquire(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("没有可用的 Snowflake workerId（0-" + MAX_WORKER_ID + " 全部被占用）");
    }

    private boolean tryAcquire(long id) {
        Boolean ok = redis.opsForValue()
                .setIfAbsent(KEY_PREFIX + id, owner, LEASE_TTL);
        return Boolean.TRUE.equals(ok);
    }
}
