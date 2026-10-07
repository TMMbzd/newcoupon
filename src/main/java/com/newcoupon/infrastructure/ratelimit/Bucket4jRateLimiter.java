package com.newcoupon.infrastructure.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 限流实现：Bucket4j 的分布式令牌桶（2026-10-08）。
 *
 * <p><b>为什么是令牌桶</b>：调研核实主流就是令牌桶（或它的等价变形 GCRA）——
 * 固定窗口因"边界最多放过两倍"被规避，滑动窗口日志因"每个请求一条记录"的存储开销被规避。
 * 令牌桶还多一个真实业务需要的能力：<b>允许突发</b>（平时攒下的令牌可以在活动开场那一瞬间用掉）。
 *
 * <p><b>状态存在 Redis</b>（由 {@link ProxyManager} 负责），所以多实例部署时是**集群级**限流，
 * 而不是"每个副本各限一份"（那会让 k 个副本变成 k 倍上限）。
 *
 * <p><b>关于主体（subject）</b>：调用方只传"限谁"，不含时间信息——见
 * {@link RateLimiter} 的类注释（上一版契约错在哪）。
 */
@Component
public class Bucket4jRateLimiter implements RateLimiter {

    /** 实现标识：日志与排障用，不再用于"按配置挑算法"（那个配置项已删） */
    public static final String ALGORITHM = "bucket4j_token_bucket";

    /** 配置指纹的本地记忆时长：它决定"别的实例改了配置"多久传播过来 */
    private static final Duration CONFIG_FINGERPRINT_TTL = Duration.ofSeconds(30);

    private final ProxyManager<String> proxyManager;

    public Bucket4jRateLimiter(ProxyManager<String> proxyManager) {
        this.proxyManager = proxyManager;
    }

    @Override
    public boolean tryAcquire(String subject, int limit, Duration window) {
        if (limit <= 0) {
            return true;   // 0=不限：连桶都不建（省一次往返）
        }
        // 用"贪婪补充"（refillGreedy）：容量 limit、每个 window 补满 limit。
        // 这是令牌桶的标准形态——均匀补充 + 允许攒着突发。
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth
                        .capacity(limit)
                        .refillGreedy(limit, window))
                .build();

        // **配置随桶状态一起持久化在 Redis 里**（分布式限流的固有特性，不是 Bucket4j 的怪癖）：
        // 桶一旦建出来，容量与补充速率就存在 Redis 里了。所以"给某租户把限额从 1000 改成 2"
        // 如果只是下次调用传个新 config，**不会生效**——桶还是按 1000 算。
        // 实测踩过：前面的用例把限额配成 1000 建了桶，清掉配置改回 2 之后，连打 20 次仍然全放行。
        //
        // 解法是**显式替换**（replaceConfiguration），并且只在"本实例观察到配置变了"时做：
        // 每次调用都替换要付一次 Redis 写，限流是高频路径，不能那么做。
        // 「本实例观察到」的信息来自一个本地缓存（subject → 配置指纹，带 TTL）——
        // 代价是多实例下配置变更的传播有延迟（≤ TTL），这与项目里租户配置缓存本身的
        // 行为是一致的（那边也是"本实例立即、其他实例等刷新"）。
        //
        // 为什么不用 Bucket4j 的隐式替换（withImplicitConfigurationReplacement）：
        // 它要求版本号**单调递增**（判据是"存储的版本 < 提供的版本"）。我先用配置哈希当版本，
        // 结果 limit=1000 的指纹 33961 > limit=2 的指纹 3023，改小限额时**不会触发替换**——
        // 哈希不单调，这个方案不成立。显式替换没有这个约束。
        Bucket bucket = proxyManager.builder().build(subject, () -> config);
        replaceConfigIfChanged(bucket, subject, limit, window, config);
        return bucket.tryConsume(1);
    }

    /**
     * 本地记住"这个主体上次用的是什么配置"，用来判断要不要触发替换。
     *
     * <p>TTL 是**传播延迟的上界**：本实例改配置立即生效；其他实例最晚在 TTL 之后的一轮
     * 比对中把桶替换过来。（与本项目租户配置缓存的行为一致——"本实例立即、其他实例等刷新"。）
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Integer> seenConfigFingerprint =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(100_000)
                    .expireAfterWrite(CONFIG_FINGERPRINT_TTL)
                    .build();

    private void replaceConfigIfChanged(Bucket bucket, String subject,
                                        int limit, Duration window, BucketConfiguration config) {
        int fingerprint = java.util.Objects.hash(limit, window.toMillis());
        Integer seen = seenConfigFingerprint.getIfPresent(subject);
        if (seen != null && seen == fingerprint) {
            return;   // 本实例最近见过同样的配置 → 桶里一定就是它，不必再写一次
        }
        // 首次见到、或配置变了：显式替换。PROPORTIONALLY = 按比例迁移已存令牌，
        // 避免"改限额的一瞬间桶被清零或灌满"（那会让限流出现一个明显的缺口或假拒绝）。
        bucket.replaceConfiguration(config, TokensInheritanceStrategy.PROPORTIONALLY);
        seenConfigFingerprint.put(subject, fingerprint);
    }

    @Override
    public String algorithm() {
        return ALGORITHM;
    }
}
