package com.newcoupon.learning.ratelimit;

import com.newcoupon.infrastructure.ratelimit.RateLimiter;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 滑动窗口限流（S2 第 4 步）——修正固定窗口的"边界双倍"缺陷。
 *
 * <p><b>固定窗口的毛病</b>：窗口是硬边界。前一秒的末尾打满上限、这一秒的开头再打满上限，
 * 于是**边界处的瞬时通过量是上限的两倍**。窗口越长，这个缝越大。
 *
 * <p><b>滑动窗口的做法</b>：同时看当前窗口与上一个窗口的计数，上一个窗口按"已经过去了多少比例"加权：
 *
 * <pre>
 *   估算值 = 上一窗口计数 × (1 - 当前窗口已过的比例) + 当前窗口计数
 * </pre>
 *
 * 刚进入新窗口时，上一窗口的权重接近 1（几乎全部计入）——边界缝就被抹平了；
 * 随着新窗口推进，上一窗口的权重线性衰减到 0。**只需要两个计数器，不用存每个请求的时间戳**
 * （那是"精确滑动窗口"，内存成本随请求数增长）。
 *
 * <p>时间同样取自 Redis 服务端（理由见 {@link TokenBucketRateLimiter}）。
 */
// 【学习材料】不在生产路径上。生产用的是 main 里的 Bucket4jRateLimiter。
// 保留这几个实现，是为了能对照着读「三种算法分别长什么样」，
// 并让 RateLimitAlgorithmsTest 用它们演示各自的形状差异（包括各自的缺点）。

public class SlidingWindowRateLimiter implements RateLimiter {

    /**
     * KEYS[1] = 计数键前缀（本脚本会派生出 :{当前窗口} 与 :{上一窗口} 两个真实键）
     * ARGV[1] = 上限
     * ARGV[2] = 窗口秒数
     *
     * @return 1 = 放行；0 = 拒绝
     */
    private static final String LUA = """
            local limit = tonumber(ARGV[1])
            local window_s = tonumber(ARGV[2])

            local t = redis.call('TIME')
            local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local window_ms = window_s * 1000

            local current_no = math.floor(now_ms / window_ms)
            local cur_key = KEYS[1] .. ':' .. current_no
            local prev_key = KEYS[1] .. ':' .. (current_no - 1)

            local cur = tonumber(redis.call('GET', cur_key) or '0')
            local prev = tonumber(redis.call('GET', prev_key) or '0')

            local elapsed_ratio = (now_ms % window_ms) / window_ms
            local estimated = prev * (1 - elapsed_ratio) + cur

            if estimated >= limit then
              return 0
            end

            redis.call('INCR', cur_key)
            redis.call('EXPIRE', cur_key, window_s * 2)
            return 1
            """;

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(LUA, Long.class);

    private final StringRedisTemplate redis;

    public SlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        if (limit <= 0) {
            return true;   // 0 = 不限
        }
        long seconds = Math.max(1, window.getSeconds());
        Long allowed = redis.execute(SCRIPT, List.of(key), String.valueOf(limit), String.valueOf(seconds));
        return allowed != null && allowed == 1L;
    }

    @Override
    public String algorithm() {
        return "sliding_window";
    }
}
