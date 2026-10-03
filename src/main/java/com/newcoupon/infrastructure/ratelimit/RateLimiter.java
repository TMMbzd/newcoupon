package com.newcoupon.infrastructure.ratelimit;

import java.time.Duration;

/**
 * 限流器端口（S1 最小版，2026-10-01）。
 *
 * <p><b>为什么现在就抽接口</b>：S1 只需要"固定窗口计数"这一种，但 S2 的治理矩阵要
 * 滑动窗口 / 令牌桶（Warm Up）/ 漏桶（匀速排队）三种实现，并且要能按维度组合挂在同一套
 * 资源名上（{@code RequestContext.resourceName()}）。接口先立住，S2 是**加法**不是改架构。
 *
 * <p><b>接口故意保持"贫瘠"</b>：只回答"放不放行"。Warm Up 与匀速排队需要返回"还要等多久"，
 * 那时接口会演进——**现在不提前设计它用不上的返回值**（YAGNI：多出来的字段没人用，
 * 却会让每个实现都要想清楚它的语义）。
 *
 * <p>实现**不负责**决定失败时放行还是拒绝（fail-open / fail-closed 是业务裁决，因场景而异：
 * 配额是可用性防线→放行，nonce 是安全防线→拒绝）。异常直接抛给调用方，由它裁决。
 */
public interface RateLimiter {

    /**
     * 尝试消耗一个配额。
     *
     * @param key   计数器键（**必须自带窗口标识**，如 {@code ...:1712345678} 表示某一秒）——
     *              窗口切换靠换 key 实现，不需要清理旧键（TTL 会收尸）
     * @param limit 窗口内上限；{@code <=0} 表示不限（直接放行，且不去碰存储）
     * @param ttl   计数器存活时长（略大于窗口，覆盖边界）
     * @return true=放行；false=超限
     */
    boolean tryAcquire(String key, int limit, Duration ttl);

    /**
     * 算法标识——与租户配置 {@code rate_limit.algorithm} 的取值一一对应。
     *
     * <p>S2 第 4 步加入：有了多个实现之后，调用方需要"按租户配置挑一个"，
     * 所以实现必须能自报家门，而不是靠 bean 名字去猜。
     */
    String algorithm();
}
