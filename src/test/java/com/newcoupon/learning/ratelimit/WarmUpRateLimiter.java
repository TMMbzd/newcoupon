package com.newcoupon.learning.ratelimit;

import com.newcoupon.infrastructure.ratelimit.RateLimiter;

import java.time.Duration;

/**
 * 冷启动预热（S2 第 5 步）——**装饰器**：包住任意限流器，把允许的阈值从低慢慢抬到目标值。
 *
 * <p><b>要解决什么</b>：服务刚启动时 JVM 还没热身（即时编译没跑、连接池是空的、缓存没装载），
 * 这时候放开全量阈值，冷启动的实例会被自己收到的正常流量打满——它此刻的处理能力只有稳态的一小部分。
 * 预热就是"**先少放一点，边跑边加**"。
 *
 * <p><b>做法</b>：把有效阈值按时间从 {@code limit / 冷启动因子} 线性抬到 {@code limit}，
 * 到达预热时长后就是全额。冷启动因子取 3（从三分之一起步），与业界常用的取值一致。
 *
 * <p><b>为什么用装饰器而不是改接口</b>：预热是"怎么用限流器"的问题，不是"限流器是什么"的问题。
 * 装饰器让四种组合（任意算法 × 开/不开预热）自动成立，而接口保持"只回答放不放行"的简单形状。
 *
 * <p><b>起点取 JVM 启动时间</b>：预热保护的是"这个进程还没热身"，所以它的生命周期属于进程，
 * 而不是某个租户或某个键。这也让"重启之后又变冷"这件事自动成立。
 */
// 【学习材料】不在生产路径上。生产用的是 main 里的 Bucket4jRateLimiter。
// 保留这几个实现，是为了能对照着读「三种算法分别长什么样」，
// 并让 RateLimitAlgorithmsTest 用它们演示各自的形状差异（包括各自的缺点）。

public class WarmUpRateLimiter implements RateLimiter {

    /** 冷启动因子：起点阈值为目标阈值的 1/3 */
    private static final int COLD_FACTOR = 3;

    private final RateLimiter delegate;
    private final long startMs;
    private final long warmupMs;

    public WarmUpRateLimiter(RateLimiter delegate, long startMs, long warmupMs) {
        this.delegate = delegate;
        this.startMs = startMs;
        this.warmupMs = warmupMs;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        return delegate.tryAcquire(key, effectiveLimit(limit), window);
    }

    /**
     * 当前时刻的有效阈值。
     *
     * <p>边界处理：不限（{@code limit <= 0}）不预热；预热时长非正不预热；
     * **时钟回拨**（elapsed 为负）按"最冷"处理——宁可少放一点，也不要在时间异常时放开全量。
     */
    int effectiveLimit(int limit) {
        if (limit <= 0 || warmupMs <= 0) {
            return limit;
        }
        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed < 0) {
            return Math.max(1, limit / COLD_FACTOR);
        }
        if (elapsed >= warmupMs) {
            return limit;
        }
        double ratio = 1.0 / COLD_FACTOR + (1.0 - 1.0 / COLD_FACTOR) * ((double) elapsed / warmupMs);
        return Math.max(1, Math.min(limit, (int) Math.floor(limit * ratio)));
    }

    @Override
    public String algorithm() {
        return delegate.algorithm() + "+warmup";
    }
}
