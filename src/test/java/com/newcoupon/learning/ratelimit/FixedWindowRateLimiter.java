package com.newcoupon.learning.ratelimit;

import com.newcoupon.infrastructure.ratelimit.RateLimiter;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 固定窗口限流（S1）：{@code INCR + 首次 EXPIRE}，与既有的 {@code IpRateGate} 同一手法。
 *
 * <p><b>为什么 S1 用固定窗口而不是令牌桶</b>：它两行代码、语义直白、精度对"保护系统"够用。
 * 它的已知缺陷是**窗口边界**：前一秒末尾与这一秒开头各打满一次，瞬时可通过 2× 上限。
 * 对"别把系统打垮"够用；对"精确计费"不够——那是 S2 换令牌桶/滑动窗口的理由（登记 L49）。
 *
 * <p>本类不做 fail-open/fail-closed 裁决：Redis 异常直接抛，由调用方按场景决定
 * （开放面配额是可用性防线 → 放行）。
 */
// 【学习材料】不在生产路径上。生产用的是 main 里的 Bucket4jRateLimiter。
// 保留这几个实现，是为了能对照着读「三种算法分别长什么样」，
// 并让 RateLimitAlgorithmsTest 用它们演示各自的形状差异（包括各自的缺点）。

public class FixedWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;

    public FixedWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration ttl) {
        if (limit <= 0) {
            return true;   // 0=不限：连计数都不做（省一次往返）
        }
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 只给"第一次创建这个计数器"的请求设过期：每个请求都 EXPIRE 会把 TTL 一直续命，
            // 窗口就永远不结束了
            redis.expire(key, ttl);
        }
        return count == null || count <= limit;
    }

    @Override
    public String algorithm() {
        return "fixed_window";
    }
}
