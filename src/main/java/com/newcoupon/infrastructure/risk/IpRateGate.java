package com.newcoupon.infrastructure.risk;

import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import com.newcoupon.infrastructure.ratelimit.RateLimiter;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * IP 频控闸门（风控前置最小版，docs/11 §6，2026-09-16 建立；2026-10-08 第 6.5 步收敛）。
 *
 * <p>挡的攻击形态：脚本无脑高频领券（无代理池的单机脚本——最高频的攻击形态）。
 *
 * <p><b>它和开放面的"IP 维度配额"是两条防线，不要合并</b>：这一条管
 * {@code /receive} 领券入口、是**风控**（错误码 7001、指标 {@code risk_ip}、默认开）；
 * 那一条管 {@code /open/**}、是**配额**（6006、{@code ip}、默认关）。
 * 对象、依据、处置都不同（前者可以直接拒，后者要能对账/提额/通知对方）——
 * 所以它们**继续用两个维度**。
 *
 * <p><b>但"计数"这件事只该有一份实现</b>（第 6.5 步改的就是这里）。原先这里手写了
 * {@code INCR} + {@code EXPIRE} + 比大小的固定窗口计数——那是项目里最后一处"重复造轮子"
 * 的限流实现（其余地方早就统一到 {@link RateLimiter} 背后的 Bucket4j 令牌桶）。
 * 用它替换之后有两个附带好处：窗口边界不再最多放行 2 倍（令牌桶是连续补充），
 * 而且键不再带"窗口号"（subject 只表示"限谁"，窗口是实现的私有职责——见 {@code RateLimiter} 的契约）。
 *
 * <p><b>关于阈值语义的变化（要按命中率复核）</b>：固定窗口 "每 60 秒 30 次"
 * → 令牌桶 "容量 30（可突发）+ 每 2 秒补 1 个"。对"挡脚本"无差，对 NAT 出口略更严
 * （突发之后只能匀速进来）。
 *
 * <p><b>Redis 挂了怎么办：放行（fail-open），但必须可见</b>。理由：频控是可用性防线，
 * 不是资损防线——资损由库存闸门/DB 双防线独立保障；因 Redis 故障误杀全部流量（fail-closed）
 * 的代价大于短暂失效。<b>而"放行"必须留下痕迹</b>：第 6.5 步之前这里连异常对象都丢掉了
 * （{@code catch (RuntimeException e) { return true; }}）——于是"风控闸已经失效"与
 * "今天没人刷"在监控上完全一样。现在补了 ERROR 日志 + fail-open 指标。
 * （同一类缺陷在同一天的第 6 步里刚修过一次，见 {@code RedisDailyQuotaChecker}。）
 */
@Component
public class IpRateGate {

    private static final Logger log = LoggerFactory.getLogger(IpRateGate.class);

    /** 与既有实现一致的前缀（运维的排查习惯不因换实现而失效） */
    static final String KEY_PREFIX = "c:risk:ip:";

    /**
     * 指标标签（ASCII 维度码）。与开放面配额的 {@code ip} 是**两个不同的值**——
     * 合并计数会看不清是哪条防线在拦人。
     */
    static final String METRIC_DIMENSION = "risk_ip";

    private final RateLimiter limiter;
    private final GovernanceMetrics metrics;
    private final boolean enabled;
    private final int windowSeconds;
    private final int maxPerWindow;

    public IpRateGate(RateLimiter limiter,
                      GovernanceMetrics metrics,
                      @Value("${coupon.risk.ip-rate.enabled:true}") boolean enabled,
                      @Value("${coupon.risk.ip-rate.window-seconds:60}") int windowSeconds,
                      @Value("${coupon.risk.ip-rate.max-per-window:30}") int maxPerWindow) {
        this.limiter = limiter;
        this.metrics = metrics;
        this.enabled = enabled;
        this.windowSeconds = windowSeconds;
        this.maxPerWindow = maxPerWindow;
    }

    /**
     * @param ip 客户端 IP（**由容器解析**，见 {@code RiskControlFilter#clientIp}——
     *           应用层不读代理头，那个头是请求方可控的）
     * @return true=放行；false=超阈拒绝（HTTP 429 + 错误码 7001 + Retry-After）
     */
    public boolean allow(String ip) {
        if (!enabled) {
            return true;   // 关掉：连计数都不做
        }
        try {
            return limiter.tryAcquire(KEY_PREFIX + ip, maxPerWindow,
                    Duration.ofSeconds(windowSeconds));
        } catch (RuntimeException e) {
            log.error("风控 IP 频控检查失败（依赖故障），放行：ip={}", ip, e);
            metrics.recordLimitFailOpen(METRIC_DIMENSION);
            return true;   // fail-open：可用性防线不因 Redis 故障误杀全量
        }
    }

    /**
     * 拒绝时建议的重试间隔（秒）——由**闸门自己**回答，而不是让过滤器去猜配置：
     * 令牌桶每 {@code windowSeconds / maxPerWindow} 秒补一个令牌，那就是"多久后可以再试"。
     *
     * <p>下限钳到 1 秒：配置里 maxPerWindow 大于 windowSeconds 时整除结果是 0，
     * 而 {@code Retry-After: 0} 等于让客户端立刻重试（把限流变成自我加压）。
     */
    public long retryAfterSeconds() {
        return Math.max(1, windowSeconds / Math.max(1, maxPerWindow));
    }

    /** 是否启用（供拒绝响应文案与排障用） */
    public boolean enabled() {
        return enabled;
    }
}
