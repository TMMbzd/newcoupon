package com.newcoupon.infrastructure.risk;

import java.time.Duration;
import java.time.LocalDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 同设备当日领券计数器（V6 4A，R_DEVICE_DAILY 的因子源）。
 *
 * <p><b>为什么自己 INCR 而不走 RateLimiter</b>：IpRateGate 的令牌桶回答
 * "这一秒还放不放"（速率语义），这里要回答"今天累计几次了"（日额度语义）——
 * 令牌桶补回来的令牌会把"累计"冲掉，语义不同。键 {@code c:risk:dev:{appId}:{deviceId}:{yyyyMMdd}}
 * 自带日界（与 IpRateGate 的 {@code c:risk:ip:} 前缀同一命名家族，运维同一套习惯）。
 *
 * <p><b>fail-open 哲学与 IpRateGate 同款</b>：本类不 catch——把 Redis 故障原样抛给
 * 规则（DeviceDailyRule），由规则决定"计数不可用=跳过+degraded+指标"。可用性防线
 * 不因 Redis 故障误杀全量；"失效"必须可见（coupon_ratelimit_failopen_total
 * {dimension=risk_device}）。
 *
 * <p><b>计数语义=领券受理尝试，不是成功发放</b>：计数发生在决策时（业务执行前），
 * 被拒的尝试也+1——攻击脚本刷 10 次失败占用的是同一个日额度，这正是想要的；
 * 幂等重放（同 requestId）也会+1，对"日 10 次"量级的粗粒度计数可接受（runbook 登记）。
 */
@Component
public class DeviceDailyCounter {

    /** 与 c:risk:ip: 同一家族的前缀（运维排查习惯不因新维度失效） */
    static final String KEY_PREFIX = "c:risk:dev:";

    /**
     * TTL 取 2 天而不是 1 天：日界由键名里的 yyyyMMdd 切换（新的一天自然换键），
     * TTL 只是兜底回收——给跨时区部署与时钟偏移留一天余量，避免"23:59 写入、
     * 00:00 过期"式的长尾泄漏或提前失效。
     */
    private static final Duration TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;

    public DeviceDailyCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 计一次并返回当日累计数（即本次是第几次）。
     *
     * @param appId 决策归属租户（领券=券模板归属 app；空安全——空时归入 "_" 桶）
     * @param deviceId 设备号（调用方已保证非空且 ≤128）
     * @param date 计数归属日（生产传今天；分离出来让"日界"在测试里可造）
     * @throws org.springframework.data.redis.RedisConnectionFailureException 等 RuntimeException
     *         Redis 不可用——**故意不兜**：fail-open 的判断在规则层，计数器只如实报告
     */
    public long increment(String appId, String deviceId, LocalDate date) {
        String key = KEY_PREFIX + (appId == null || appId.isBlank() ? "_" : appId)
                + ":" + deviceId + ":" + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 只有键的第一次写入设 TTL（此后每次都重设会变成滑动过期，日界失真）
            redis.expire(key, TTL);
        }
        return count == null ? 0L : count;
    }
}
