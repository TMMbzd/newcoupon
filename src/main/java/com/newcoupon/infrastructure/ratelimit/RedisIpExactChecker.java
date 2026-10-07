package com.newcoupon.infrastructure.ratelimit;

import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * IP 维度限流的实现（迁移第 6 步，2026-10-08）——由 {@link IpExactSlot} 在链上调用。
 *
 * <p><b>为什么是"自建 Slot + 复用令牌桶"而不是自己写计数</b>：
 * 官方规则模型表达不了"按无界维度（IP）精确限流"（理由见 {@link IpExactSlot}），
 * 但**计数本身是已经被解决的问题**——{@link RateLimiter} 背后是 Bucket4j 的分布式令牌桶，
 * 状态在 Redis 里（所以多实例是**集群级**限额，而不是每个副本各限一份）。
 * 自建的部分只有"挂到哪、什么时候判、拒绝长什么样"，计数不重复造。
 *
 * <p><b>语义与迁移前一致</b>：
 * <ul>
 *   <li>键：{@code c:rl:ip:{ip}}（前缀沿用，运维既有的排查习惯不因换实现而失效）；</li>
 *   <li>开关与阈值来自**全站配置** {@code coupon.ratelimit.ip.enabled} / {@code .qps}
 *       （IP 是平台级维度，不是租户级的——它不读租户配置）；</li>
 *   <li>{@code enabled=false} 或 {@code qps<=0} 表示不启用（本项目"0 = 不限"的同一语义）；</li>
 *   <li>Redis 故障 → **fail-open 且可见**（见下）。</li>
 * </ul>
 *
 * <p><b>修掉的一个缺陷：窗口是 2 秒</b>。迁移前调用处传的是 {@code Duration.ofSeconds(2)}，
 * 那个 2 是固定窗口时代"键的 TTL"的遗留值；换成令牌桶之后，这个参数变成了
 * **桶的补充周期**（源码：{@code Refill} 的构造器把 period 整体存成 {@code periodNanos}，
 * 贪婪补充即在这段时间里匀出 {@code tokens} 个令牌），于是
 * "qps=10"实际拿到的是**每秒 5 个**——配置项名与行为不符，而且没有任何东西会提示。
 * 这里固定为 1 秒：配置项叫 qps，窗口就必须是 1 秒。
 *
 * <p><b>fail-open 与 {@link DailyQuotaSlot} 同款裁决</b>：限流是**可用性/公平性**防线，
 * 不是资损防线；Redis 挂时误杀全部来源，代价大于短暂失控。
 * 对照：nonce 检查是安全防线，所以它 fail-closed。**同一个问题，两种结论，取决于它是哪一类防线。**
 */
@Component
public class RedisIpExactChecker implements IpExactSlot.IpChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisIpExactChecker.class);

    /** 与迁移前一致的前缀 */
    static final String IP_KEY_PREFIX = "c:rl:ip:";

    /**
     * 指标标签（ASCII 维度码）。维度名对 Prometheus 是**长期契约**——
     * 改了等于历史序列清零，所以它在这里与 {@code RiskControlFilter} 的 {@code risk_ip}
     * 必须是两个不同的值（那里是风控频控，这里是配额维度）。
     */
    static final String METRIC_DIMENSION = "ip";

    /**
     * 窗口 = **1 秒**。配置项叫 {@code qps}（每秒上限），窗口就必须是 1 秒
     * （迁移前是 2 秒，导致实际速率只有配置值的一半——见类注释）。
     */
    static final Duration WINDOW = Duration.ofSeconds(1);

    /** 拒绝时建议的重试间隔（秒）：QPS 窗口的长度 */
    static final long RETRY_AFTER_SECONDS = 1;

    private final RateLimiter limiter;
    private final GovernanceMetrics metrics;
    private final boolean enabled;
    private final int qps;

    public RedisIpExactChecker(RateLimiter limiter,
                               GovernanceMetrics metrics,
                               @Value("${coupon.ratelimit.ip.enabled:false}") boolean enabled,
                               @Value("${coupon.ratelimit.ip.qps:0}") int qps) {
        this.limiter = limiter;
        this.metrics = metrics;
        this.enabled = enabled;
        this.qps = qps;
    }

    @Override
    public void check(String ip) throws IpExactBlockException {
        if (!enabled || qps <= 0) {
            return;   // 未启用：连计数都不做
        }
        boolean allowed;
        try {
            allowed = limiter.tryAcquire(IP_KEY_PREFIX + ip, qps, WINDOW);
        } catch (RuntimeException e) {
            // **fail-open 必须可见**：Redis 挂掉时放行是对的，但那样仪表盘上会呈现
            // 「拒绝数为 0、一切正常」的假象——实际上防线已经失效。
            // 没有这条指标，「防线失效」与「流量本来就小」在监控上完全无法区分。
            log.error("IP 维度限流检查失败（Redis 不可用），放行：ip={}", ip, e);
            metrics.recordLimitFailOpen(METRIC_DIMENSION);
            return;
        }
        if (!allowed) {
            // 拒绝的**指标打点在出口**（BlockRejection.writeTo），不在这里——
            // 一处打点就不会出现"两条路径各记一次"的双计数。
            log.warn("IP 维度拒绝：ip={}（上限 {}/秒）", ip, qps);
            throw new IpExactBlockException(ip);
        }
    }
}
