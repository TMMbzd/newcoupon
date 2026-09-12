package com.newcoupon.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 调度互斥锁（2026-09-12，多实例定时任务互斥）：Redis SET NX PX 抢锁 + Lua 比对释放。
 *
 * <p>设计立场（与全套系统一致）：**正确性靠幂等（条件更新+唯一索引），互斥只是省力**——
 * 没有这把锁，多实例重复执行也不会改错数据（CAS 恰好一家改中），代价是双倍的扫描量与
 * DB 读负载。加锁把这代价去掉，并把"每轮谁在跑"变成可观测的。
 *
 * <p>三个细节为什么这么写：
 * <ul>
 *   <li>值=持有者标识：释放时只删自己持有的锁——防止实例 A 的锁过期后实例 B 已接管，
 *       A 的 finally 又把 B 的锁删了（误删他人锁是分布式锁最常见事故）；</li>
 *   <li>TTL 兜底：持锁实例崩溃，锁最多存活到 TTL 过期，下一轮自动恢复——锁本身不依赖释放动作；</li>
 *   <li>不续期、不做可重入：调度场景一轮一抢，简单即正确（需要长持锁时把 TTL 设大于最坏轮时长）。</li>
 * </ul>
 */
@Component
public class RedisScanLock {

    private static final DefaultRedisScript<Long> COMPARE_AND_DEL = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) "
                    + "else return 0 end", Long.class);

    private final StringRedisTemplate redis;

    public RedisScanLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 抢锁：SET key owner NX PX ttl——原子地"不存在才写+带过期" */
    public boolean tryLock(String key, String owner, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, ttl));
    }

    /** 释放：仅当值仍是自己的持有者标识才删（Lua 原子，防误删他人锁） */
    public void unlock(String key, String owner) {
        redis.execute(COMPARE_AND_DEL, List.of(key), owner);
    }
}
