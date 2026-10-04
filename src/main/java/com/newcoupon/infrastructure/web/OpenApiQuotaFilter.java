package com.newcoupon.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.CachedOpenAppReader;
import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.application.service.CachedOpenAppReader.AppAuth;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.infrastructure.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 接入方配额过滤器（S1 第 7 步）——只拦 {@code /open/**}，两个维度：
 *
 * <pre>
 *   QPS     c:open:qps:{appId}:{epochSecond}   上限 open_app.qps_limit
 *   日配额  c:open:quota:{appId}:{yyyyMMdd}    上限 open_app.daily_quota
 * </pre>
 *
 * <p><b>为什么是 appId 维度而不是 IP 维度</b>：IP 频控（{@code RiskControlFilter}）防的是
 * "没人管的脚本刷券"；appId 配额管的是"**已接入的合作方**用了多少"——两者的对象、依据与
 * 处置都不同（前者可以直接拒，后者要能对账、能提额、能通知对方）。同一个入口挂两道闸，
 * 各管各的。
 *
 * <p><b>fail-open</b>（与 {@code IpRateGate} 同款裁决）：Redis 故障时放行。理由也一样——
 * 配额是**可用性/公平性**防线，不是资损防线；Redis 挂时误杀全部接入方，代价大于短暂失控。
 * 对照：nonce 检查是安全防线，所以它 fail-closed。<b>同一个问题，两种结论，取决于它是哪一类防线。</b>
 *
 * <p>{@code limit = 0} 表示不限（内部应用）：连计数都不做。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)   // 认证(+5) → 配额(+6) → 幂等(+7)
public class OpenApiQuotaFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiQuotaFilter.class);

    private static final String OPEN_PREFIX = "/open/";
    static final String QPS_KEY_PREFIX = "c:open:qps:";
    static final String DAILY_KEY_PREFIX = "c:open:quota:";
    private static final String RETRY_AFTER = "Retry-After";

    /** 默认算法：租户没配或配了不存在的算法时用它 */
    private static final String DEFAULT_ALGORITHM = "sliding_window";

    private final CachedOpenAppReader appReader;
    private final ObjectMapper objectMapper;
    private final TenantConfigService tenantConfigService;

    /** 算法标识 → 限流器实现。**按实现自报的 algorithm() 建索引**，而不是靠 bean 名字去猜 */
    private final Map<String, RateLimiter> limitersByAlgorithm;

    /** JVM 启动时刻：冷启动预热的起点（预热保护的是"这个进程还没热身"） */
    private static final long JVM_START_MS =
            java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();

    public OpenApiQuotaFilter(CachedOpenAppReader appReader,
                              List<RateLimiter> limiters,
                              ObjectMapper objectMapper,
                              TenantConfigService tenantConfigService) {
        this.appReader = appReader;
        this.objectMapper = objectMapper;
        this.tenantConfigService = tenantConfigService;
        this.limitersByAlgorithm = limiters.stream()
                .collect(java.util.stream.Collectors.toMap(RateLimiter::algorithm, l -> l));
        log.info("限流器已装配：{}", limitersByAlgorithm.keySet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(OPEN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String appId = RequestContext.current().appId();
        if (appId == null) {
            // 认证过滤器已拦下无身份请求；走到这里说明接线异常，放行（不静默吞请求）
            chain.doFilter(request, response);
            return;
        }
        Optional<AppAuth> found = appReader.find(appId);   // 命中本地缓存（认证时刚查过）
        if (found.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        AppAuth app = found.get();

        if (!withinQps(appId, app.qpsLimit(), response)) {
            return;
        }
        if (!withinDailyQuota(appId, app.dailyQuota(), response)) {
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean withinQps(String appId, int registeredLimit, HttpServletResponse response) throws IOException {
        int limit = resolveLimit(appId, TenantConfigKey.RATE_LIMIT_QPS, registeredLimit);
        if (limit <= 0) {
            return true;
        }
        String key = QPS_KEY_PREFIX + appId + ":" + (System.currentTimeMillis() / 1000);
        if (acquireSafely(appId, key, limit, Duration.ofSeconds(2))) {
            return true;
        }
        reject(response, ErrorCode.QPS_LIMIT_EXCEEDED, "1");   // 下一秒就是新窗口
        return false;
    }

    private boolean withinDailyQuota(String appId, int registeredLimit, HttpServletResponse response) throws IOException {
        int limit = resolveLimit(appId, TenantConfigKey.RATE_LIMIT_DAILY_QUOTA, registeredLimit);
        if (limit <= 0) {
            return true;
        }
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        // 计数器活到次日 00:05（而不是 24h）：按自然日重置，跨天边界留 5 分钟余量
        LocalDateTime expireAt = LocalDate.now().plusDays(1).atTime(0, 5);
        String key = DAILY_KEY_PREFIX + appId + ":" + day;
        if (acquireSafely(appId, key, limit, Duration.between(LocalDateTime.now(), expireAt))) {
            return true;
        }
        long secondsUntilTomorrow = Duration.between(LocalDateTime.now(),
                LocalDate.now().plusDays(1).atStartOfDay()).getSeconds();
        reject(response, ErrorCode.DAILY_QUOTA_EXCEEDED, String.valueOf(Math.max(1, secondsUntilTomorrow)));
        return false;
    }

    /**
     * 阈值来源：**租户配置优先，未配置回落登记表**（S2 第 3 步）。
     *
     * <p>必须区分"未配置"与"显式配 0"——前者用 {@link TenantConfigService#get} 返回 null 判定，
     * 后者是"明确要求不限"。混为一谈会让"想给某租户放开限制"变成一个做不到的操作。
     */
    private int resolveLimit(String appId, TenantConfigKey key, int registeredLimit) {
        String configured = tenantConfigService.get(appId, key);
        if (configured == null || configured.isBlank()) {
            return registeredLimit;   // 未配置 → 回落登记表（保持 S1 的既有入口）
        }
        return tenantConfigService.getInt(appId, key);
    }

    /**
     * 按租户配置挑算法并执行；**fail-open**（配额是可用性防线，不因中间件故障误杀全量接入方）。
     *
     * <p>算法来自租户配置 {@code rate_limit.algorithm}；配了不存在的算法会回落到默认算法
     * （而不是抛异常）——配置写错是运维问题，不该变成用户的请求失败。
     */
    private boolean acquireSafely(String appId, String key, int limit, Duration ttl) {
        RateLimiter limiter = resolveLimiter(appId);
        try {
            return limiter.tryAcquire(key, limit, ttl);
        } catch (RuntimeException e) {
            log.error("配额检查失败（Redis 不可用），放行：key={}", key, e);
            return true;
        }
    }

    /**
     * 组装该租户实际使用的限流器：**先按配置选算法，再按配置决定要不要套上冷启动预热**。
     *
     * <p>装饰器在这里"现场组装"而不是预先注册成 bean：预热参数是租户级的，
     * 组合数 = 算法数 × 预热配置数，预先注册会爆炸；而且包装对象只有三个字段，构造成本可忽略。
     */
    private RateLimiter resolveLimiter(String appId) {
        RateLimiter limiter = baseLimiter(appId);
        int warmupSeconds = tenantConfigService.getInt(appId, TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS);
        if (warmupSeconds > 0) {
            return new com.newcoupon.infrastructure.ratelimit.WarmUpRateLimiter(
                    limiter, JVM_START_MS, warmupSeconds * 1000L);
        }
        return limiter;
    }

    /** 按配置选算法；配了不存在的算法回落默认（配置写错是运维问题，不该变成用户的请求失败） */
    private RateLimiter baseLimiter(String appId) {
        String algorithm = tenantConfigService.get(appId, TenantConfigKey.RATE_LIMIT_ALGORITHM);
        RateLimiter limiter = algorithm == null ? null : limitersByAlgorithm.get(algorithm);
        if (limiter == null) {
            if (algorithm != null) {
                log.warn("租户配置了未知的限流算法，回落默认：appId={} algorithm={}", appId, algorithm);
            }
            limiter = limitersByAlgorithm.get(DEFAULT_ALGORITHM);
        }
        return limiter;
    }

    private void reject(HttpServletResponse response, ErrorCode code, String retryAfterSeconds)
            throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // 429 必须告诉对方多久后重试——否则接入方只能瞎猜（或死循环重试，把限流变成自杀）
        response.setHeader(RETRY_AFTER, retryAfterSeconds);
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code)));
    }
}
