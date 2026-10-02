package com.newcoupon.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.CachedOpenAppReader;
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

    private final CachedOpenAppReader appReader;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public OpenApiQuotaFilter(CachedOpenAppReader appReader,
                              RateLimiter rateLimiter,
                              ObjectMapper objectMapper) {
        this.appReader = appReader;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
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

    private boolean withinQps(String appId, int limit, HttpServletResponse response) throws IOException {
        if (limit <= 0) {
            return true;
        }
        String key = QPS_KEY_PREFIX + appId + ":" + (System.currentTimeMillis() / 1000);
        if (acquireSafely(key, limit, Duration.ofSeconds(2))) {
            return true;
        }
        reject(response, ErrorCode.QPS_LIMIT_EXCEEDED, "1");   // 下一秒就是新窗口
        return false;
    }

    private boolean withinDailyQuota(String appId, int limit, HttpServletResponse response) throws IOException {
        if (limit <= 0) {
            return true;
        }
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        // 计数器活到次日 00:05（而不是 24h）：按自然日重置，跨天边界留 5 分钟余量
        LocalDateTime expireAt = LocalDate.now().plusDays(1).atTime(0, 5);
        String key = DAILY_KEY_PREFIX + appId + ":" + day;
        if (acquireSafely(key, limit, Duration.between(LocalDateTime.now(), expireAt))) {
            return true;
        }
        long secondsUntilTomorrow = Duration.between(LocalDateTime.now(),
                LocalDate.now().plusDays(1).atStartOfDay()).getSeconds();
        reject(response, ErrorCode.DAILY_QUOTA_EXCEEDED, String.valueOf(Math.max(1, secondsUntilTomorrow)));
        return false;
    }

    /** fail-open：限流器抛异常时放行（配额是可用性防线，不因中间件故障误杀全量接入方） */
    private boolean acquireSafely(String key, int limit, Duration ttl) {
        try {
            return rateLimiter.tryAcquire(key, limit, ttl);
        } catch (RuntimeException e) {
            log.error("配额检查失败（Redis 不可用），放行：key={}", key, e);
            return true;
        }
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
