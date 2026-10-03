package com.newcoupon.infrastructure.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 令牌桶限流（S2 第 4 步）——允许突发的场景用它。
 *
 * <p><b>形状</b>：桶里按固定速率攒令牌（上限 = 桶容量）；请求来了扣一个令牌，扣不到就拒。
 * 桶是满的说明"最近没人用"，于是攒下的令牌允许**一次性突发到容量上限**——
 * 这正是它与滑动窗口的行为差异：滑动窗口严格按时间窗计数，令牌桶允许"攒着以后一次用掉"。
 *
 * <p><b>两个实现细节</b>：
 * <ul>
 *   <li><b>桶的初始状态是满的</b>：否则服务刚启动时第一个请求必被拒（冷启动体验差）。
 *       代价是"重启会获得一次突发额度"——对限流这种"保护性"能力来说可以接受。</li>
 *   <li><b>时间取自 Redis 服务端</b>（{@code TIME} 命令）而不是应用进程：多个实例的时钟不同步时，
 *       "过了多少时间"必须只有一个答案，否则限流会忽松忽紧。这与本项目令牌打分用服务端时间是同一个理由。</li>
 * </ul>
 *
 * <p><b>为什么用 Lua</b>：读令牌、算补充、扣减、写回必须原子完成；分成几条命令会出现
 * "两个请求同时读到还有 1 个令牌、都扣成功"的超发。
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    /**
     * KEYS[1] = 桶的键
     * ARGV[1] = 容量（= limit）
     * ARGV[2] = 每秒补充的令牌数（= limit / 窗口秒数）
     * ARGV[3] = 键的存活秒数
     *
     * @return 1 = 放行；0 = 拒绝
     */
    private static final String LUA = """
            local cap = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])

            local t = redis.call('TIME')
            local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = cap          -- 桶的初始状态是满的
              ts = now_ms
            end

            local delta = math.max(0, now_ms - ts) / 1000.0
            tokens = math.min(cap, tokens + delta * rate)

            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now_ms)
            redis.call('EXPIRE', KEYS[1], ttl)
            return allowed
            """;

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(LUA, Long.class);

    private final StringRedisTemplate redis;

    public TokenBucketRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        if (limit <= 0) {
            return true;   // 0 = 不限
        }
        long seconds = Math.max(1, window.getSeconds());
        long allowed = redis.execute(SCRIPT, List.of(key),
                String.valueOf(limit),                    // 容量
                String.valueOf((double) limit / seconds),  // 每秒补充速率
                String.valueOf(seconds * 2));              // 空闲两个窗口即过期（等价于桶又满了）
        return allowed == 1L;
    }

    @Override
    public String algorithm() {
        return "token_bucket";
    }
}
