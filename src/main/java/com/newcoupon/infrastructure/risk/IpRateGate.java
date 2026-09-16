package com.newcoupon.infrastructure.risk;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * IP 频控闸门（风控前置最小版，docs/11 §6，2026-09-16）。
 *
 * <p>挡的攻击形态：脚本无脑高频领券（无代理池的单机脚本——最高频的攻击形态）。
 * 机制：固定窗口计数——{@code INCR c:risk:ip:{ip}:{窗口起始}} + EXPIRE，超阈值拒绝。
 *
 * <p>取舍（docs/11 §6 裁决）：固定窗口而非滑动窗口/令牌桶——实现 5 行、精度足够
 * （窗口边界最多放行 2 倍瞬时，对"挡脚本"无差）；单 IP 维度会误伤 NAT 出口
 * （公司/校园网共享 IP）——阈值留余量+可配置；不防分布式代理池（那需要设备指纹——
 * 刻意不做项，触发条件见 docs/11 §7）。
 *
 * <p> Redis 挂了怎么办：放行（fail-open）。理由：频控是可用性防线不是资损防线——
 * 资损由库存闸门/DB 双防线独立保障；频控因 Redis 故障误杀全部流量（fail-closed）
 * 的代价大于短暂失效。
 */
@Component
public class IpRateGate {

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int windowSeconds;
    private final int maxPerWindow;

    public IpRateGate(StringRedisTemplate redis,
                      @Value("${coupon.risk.ip-rate.enabled:true}") boolean enabled,
                      @Value("${coupon.risk.ip-rate.window-seconds:60}") int windowSeconds,
                      @Value("${coupon.risk.ip-rate.max-per-window:30}") int maxPerWindow) {
        this.redis = redis;
        this.enabled = enabled;
        this.windowSeconds = windowSeconds;
        this.maxPerWindow = maxPerWindow;
    }

    /**
     * @param ip 客户端 IP（提取逻辑在 Filter——代理头优先）
     * @return true=放行；false=超阈拒绝（HTTP 429 + 错误码 7001）
     */
    public boolean allow(String ip) {
        if (!enabled) {
            return true;
        }
        try {
            long windowStart = System.currentTimeMillis() / (windowSeconds * 1000L);
            String key = "c:risk:ip:" + ip + ":" + windowStart;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofSeconds(windowSeconds * 2L));   // 2倍窗口：覆盖边界跨期的残留
            }
            return count == null || count <= maxPerWindow;
        } catch (RuntimeException e) {
            return true;   // fail-open：可用性防线不因 Redis 故障误杀全量
        }
    }
}
