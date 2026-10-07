package com.newcoupon.infrastructure.ratelimit;

import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 日配额的 Redis 实现（迁移第 5 步，2026-10-08）。
 *
 * <p><b>为什么日配额继续用 Redis 而不是 Sentinel 的规则</b>：Sentinel 的规则模型表达不了
 * "自然日重置 + Retry-After=到明天的秒数"（源码级理由见 {@link DailyQuotaSlot} 的注释）。
 * 所以这一块**沿用了迁移前的 Redis 计数**——只是换了挂载方式：从"过滤器里手写的判断"
 * 变成"Sentinel 处理链上的一个 slot"。
 *
 * <p><b>语义与迁移前完全一致</b>：
 * <ul>
 *   <li>键：{@code c:open:quota:{appId}:{yyyyMMdd}}——按自然日分键；</li>
 *   <li>过期：到**次日 00:05**（不是 24 小时）——按自然日重置，跨天边界留 5 分钟余量；</li>
 *   <li>上限：租户配置 {@code rate_limit.daily_quota} 优先，未配置回落登记表
 *       {@code open_app.daily_quota}；{@code <= 0} 表示不限；</li>
 *   <li>拒绝：抛 {@link ErrorCode#DAILY_QUOTA_EXCEEDED}（6007），并带上"到明天还有多少秒"
 *       ——由拒绝处理器读出来放进 {@code Retry-After}。</li>
 * </ul>
 *
 * <p><b>一个刻意的取舍</b>：这里**不做事后回滚**（比如请求后来失败了要不要把计数减回去）。
 * 迁移前也是这么做的——日配额是**准入控制**，不是精确计费；把它做成"事务性计数"
 * 会让每次请求多两次 Redis 往返，而收益只是让计数更"好看"。
 * （真要精确计费，应该走"用量事件 + 对账"，那是另一条路。）
 */
@Component
public class RedisDailyQuotaChecker implements DailyQuotaSlot.DailyQuotaChecker {

    /** 与迁移前一致的前缀（运维的既有排查习惯不要因为换实现而失效） */
    static final String DAILY_KEY_PREFIX = "c:open:quota:";

    private final StringRedisTemplate redis;
    private final TenantConfigService tenantConfig;
    private final OpenAppRepository appRepository;

    public RedisDailyQuotaChecker(StringRedisTemplate redis,
                                  TenantConfigService tenantConfig,
                                  OpenAppRepository appRepository) {
        this.redis = redis;
        this.tenantConfig = tenantConfig;
        this.appRepository = appRepository;
    }

    @Override
    public void check(String appId) throws DailyQuotaBlockException {
        int limit = resolveDailyLimit(appId);
        if (limit <= 0) {
            return;   // 0 = 不限（与迁移前同一语义）
        }

        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = DAILY_KEY_PREFIX + appId + ":" + day;

        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // **只给第一次创建设过期**：每次请求都 EXPIRE 会把 TTL 一直续命，窗口就永不结束了
            // （与固定窗口限流器同一个道理）
            redis.expire(key, Duration.ofSeconds(secondsUntilTomorrow() + 300));
        }

        if (count != null && count > limit) {
            // **必须抛 BlockException 的子类**：抛业务异常会被 CtSph 的 catch(Throwable)
            // 当成 slot 内部错误吞掉并放行（见 DailyQuotaBlockException 的注释）
            throw new DailyQuotaBlockException(appId, secondsUntilTomorrow());
        }
    }

    @Override
    public ErrorCode errorCode() {
        return ErrorCode.DAILY_QUOTA_EXCEEDED;
    }

    /** 到明天 0 点的秒数——拒绝响应里的 {@code Retry-After} 用它（"多久后重试"的准确答案） */
    public static long secondsUntilTomorrow() {
        return Math.max(1, Duration.between(LocalDateTime.now(),
                LocalDate.now().plusDays(1).atStartOfDay()).getSeconds());
    }

    /**
     * 该租户的每日上限：**租户配置优先，未配置回落登记表**（与迁移前的 {@code resolveLimit} 同一套规则）。
     */
    private int resolveDailyLimit(String appId) {
        String configured = tenantConfig.get(appId, TenantConfigKey.RATE_LIMIT_DAILY_QUOTA);
        if (configured != null && !configured.isBlank()) {
            return tenantConfig.getInt(appId, TenantConfigKey.RATE_LIMIT_DAILY_QUOTA);
        }
        return appRepository.findApp(appId).map(OpenApp::dailyQuota).orElse(0);
    }
}
