package com.newcoupon.infrastructure.ratelimit;

import java.time.Duration;

/**
 * 限流器端口（2026-10-08 重新定义契约，替换自研的三算法实现）。
 *
 * <p><b>这个接口为什么长这样——上一版错在哪</b>：
 * 上一版是 {@code tryAcquire(String key, int limit, Duration ttl)}，javadoc 还写着
 * "key <b>必须自带窗口标识</b>（如 {@code ...:1712345678} 表示某一秒）"。
 * 那是**把第一个实现（固定窗口）的内部做法，写成了对所有实现的强制要求**：
 * <ul>
 *   <li>固定窗口确实靠"换 key"切窗口；</li>
 *   <li>但滑动窗口要读"上一窗口"的键——而调用方给的键每秒就换，它读不到；</li>
 *   <li>令牌桶把状态和最后填充时间存在同一个键里，也不需要键带时间。</li>
 * </ul>
 * 后果是：<b>配了滑动窗口的租户，实际拿到的是退化的固定窗口</b>，而没有任何东西会告诉他。
 *
 * <p><b>正确的契约</b>（调研核实：Guava / Bucket4j / Redisson / Sentinel / Resilience4j /
 * Spring Cloud Gateway / redis-cell 七家**没有一家**要求调用方传带时间标识的键）：
 * 调用方只说"<b>限谁</b>"，时间与存储键的派生是实现的私有职责。
 *
 * <p><b>关于 {@code window}</b>：它是**规则**的一部分（限多少 / 多久），不是每次调用才有的信息。
 * 之所以还留在方法签名上，是为了让这个项目现有的"按租户解析配置再调用"的写法保持直白；
 * Bucket4j 那一版实现会把它转成桶的容量与补充速率。
 *
 * <p><b>{@code limit <= 0} 的语义是"不限"</b>——直接放行，且不去碰存储
 * （省一次往返，也让"把某租户放开"不需要特判）。
 */
public interface RateLimiter {

    /**
     * 尝试消耗一个配额。
     *
     * @param subject 限流主体——**"限谁"，纯标识，不含任何时间信息**。
     *                如 {@code appId}、IP、资源名。窗口怎么切、状态怎么存，由实现决定。
     * @param limit   窗口内上限；{@code <= 0} 表示不限（直接放行，不碰存储）
     * @param window  窗口长度（限多久）
     * @return true=放行；false=超限
     */
    boolean tryAcquire(String subject, int limit, Duration window);

    /**
     * 实现标识（{@code bucket4j} 等）——用于日志与排障，**不再用于"按配置挑算法"**。
     *
     * <p>上一版有个 {@code rate_limit.algorithm} 配置项让租户在
     * "固定窗口 / 滑动窗口 / 令牌桶"之间选。调研明确指出<b>业界没有这种切换</b>：
     * 主流就是令牌桶（或 GCRA），固定窗口因边界双倍被规避、滑动窗口日志因存储开销被规避。
     * 那个配置项是自发明的，而且由于上面说的键设计问题它还是坏的，所以已经删掉。
     */
    String algorithm();
}
