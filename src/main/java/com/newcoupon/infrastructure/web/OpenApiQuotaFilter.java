package com.newcoupon.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.CachedOpenAppReader;
import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.application.service.CachedOpenAppReader.AppAuth;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.infrastructure.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
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

    private final CachedOpenAppReader appReader;
    private final ObjectMapper objectMapper;
    private final TenantConfigService tenantConfigService;

    /** 算法标识 → 限流器实现。**按实现自报的 algorithm() 建索引**，而不是靠 bean 名字去猜 */
    /** 限流器（2026-10-08 起只有一个实现：Bucket4j 的分布式令牌桶） */
    private final RateLimiter limiter;

    /**
     * 平台级限流维度（S2 第 6 步）。
     *
     * <p><b>为什么与 appId 维度分开</b>：appId 维度是**租户级**的——"这个客户能用多少"
     * 是商业约定，放租户配置；IP 与接口维度是**平台级**的——"一个来源 / 一个接口最多打多少"
     * 是自我保护，与具体客户无关，放全站配置。
     *
     * <p>**默认关闭**：打开它们会改变所有接入方的可见行为，应当由运维显式决定。
     */
    private final boolean ipEnabled;
    private final int ipQps;
    private final boolean resourceEnabled;
    private final int resourceQps;
    /** 治理指标（S2 第 11 步） */
    private final com.newcoupon.infrastructure.metrics.GovernanceMetrics governanceMetrics;

    /** JVM 启动时刻：冷启动预热的起点（预热保护的是"这个进程还没热身"） */
    private static final long JVM_START_MS =
            java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();

    public OpenApiQuotaFilter(CachedOpenAppReader appReader,
                              RateLimiter limiter,
                              ObjectMapper objectMapper,
                              TenantConfigService tenantConfigService,
                              @Value("${coupon.ratelimit.ip.enabled:false}") boolean ipEnabled,
                              @Value("${coupon.ratelimit.ip.qps:0}") int ipQps,
                              @Value("${coupon.ratelimit.resource.enabled:false}") boolean resourceEnabled,
                              @Value("${coupon.ratelimit.resource.qps:0}") int resourceQps,
            com.newcoupon.infrastructure.metrics.GovernanceMetrics governanceMetrics) {
        this.governanceMetrics = governanceMetrics;
        this.appReader = appReader;
        this.objectMapper = objectMapper;
        this.tenantConfigService = tenantConfigService;
        this.ipEnabled = ipEnabled;
        this.ipQps = ipQps;
        this.resourceEnabled = resourceEnabled;
        this.resourceQps = resourceQps;
        this.limiter = limiter;
        log.info("限流器已装配：{}", limiter.algorithm());
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

        // 平台级维度先检查（自我保护优先于商业约定），任一超限即拒
        if (!withinPlatformDimension("IP", "ip", "c:rl:ip:" + RiskControlFilter.clientIp(request),
                ipQps, ipEnabled, response)) {
            return;
        }
        if (!withinPlatformDimension("接口", "resource", "c:rl:res:" + RequestContext.current().resourceName(),
                resourceQps, resourceEnabled, response)) {
            return;
        }
        // 租户维度（QPS + 日配额）：**一次 entry 同时承载两者**（2026-10-08 迁移到 Sentinel）
        //   · QPS 由 Sentinel 的 FlowRule(resource=open_api, limitApp=appId) 判
        //   · 日配额由处理链上的 DailyQuotaSlot 判（官方规则模型表达不了，见那个类的注释）
        // 为什么用聚合资源名而不是接口路径：租户配额的语义是"**该租户跨所有接口的总量**"，
        // 按接口各自限会让总量随接口数放大（详见 TenantFlowRuleMapper 的注释）。
        if (!enterTenantDimension(appId, response)) {
            return;   // 拒绝响应已在本方法内写出（Filter 层抛异常到不了 @RestControllerAdvice）
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // **必须 exit**：Entry 与 exit 要同线程 LIFO 配对，漏了统计会错乱，
            // 而且 Tomcat 复用线程时会把上下文串给下一个请求（与 RequestContext 同一个道理）。
            exitTenantDimension(appId);
        }
    }

    /**
     * 进入租户维度的聚合资源。
     *
     * <p><b>为什么抛 BizException 而不是自己写响应</b>：手写 {@code SphU.entry} 不会经过
     * 适配器的 {@code BlockExceptionHandler}（那个只服务适配器自己的埋点），
     * 所以要把 {@code BlockException} 转成项目自己的异常，交给
     * {@code GlobalExceptionHandler} 统一输出——这样"限流被拒"与"日配额被拒"
     * 的响应形态完全一致（429 + 业务码 + Retry-After）。
     *
     * @return true = 放行；false = 已被拒（响应已写出）
     */
    private boolean enterTenantDimension(String appId, HttpServletResponse response) throws IOException {
        // **必须自己建立上下文**（否则 origin 为空，按租户的规则与检查全都匹配不上）。
        //
        // 这里踩过一个坑：origin 是由 `ContextUtil.enter(name, origin)` 建立的，
        // 而适配器是在 **Interceptor** 里做的——我们的 entry 在 **Filter** 里，
        // Filter 先于 Interceptor 执行，那时上下文还没建，于是 origin 是空串。
        // 症状极具迷惑性：Slot 被调用了、资源名也对、checker 也在，**只有 origin 是空的**。
        //
        // 在这里 enter 还有个好处：`ContextUtil.enter` 只在"调用链入口第一次"生效，
        // 所以随后适配器在 Interceptor 里再 enter 不会覆盖——**整条链共用同一个 origin**。
        com.alibaba.csp.sentinel.context.ContextUtil.enter(CONTEXT_NAME, appId);
        try {
            // 签名是 entry(name, EntryType, count, args...)——**没有** resourceType 参数的那个变体
            // （研读提到的 "(name, resourceType, EntryType)" 是另一个只有 3 参的重载）
            tenantDimensionEntry.set(com.alibaba.csp.sentinel.SphU.entry(
                    com.newcoupon.infrastructure.ratelimit.TenantFlowRuleMapper.OPEN_API_RESOURCE,
                    com.alibaba.csp.sentinel.EntryType.IN,
                    1,
                    appId));
            return true;
        } catch (com.alibaba.csp.sentinel.slots.block.BlockException e) {
            // **必须在这里自己写响应**（2026-10-08 踩坑）：
            // 这是 **Filter** 层，而 `@RestControllerAdvice` 只处理 **Controller** 层抛出的异常。
            // 第一版我把 BlockException 转成 BizException 往外抛，结果响应是 **500**——
            // Filter 的异常交给的是 Servlet 容器，不是 Spring MVC 的异常处理器。
            //
            // 而适配器那条路径（Interceptor 里的埋点）走的是 `BlockExceptionHandler`，
            // 所以两条路径的处理方式不同：**手写埋点要对自己的拒绝负责**。
            writeBlockResponse(response, e);
            exitTenantDimension(appId);
            return false;
        }
    }

    /**
     * 把 Sentinel 的拒绝写成项目契约（429 + JSON + Retry-After + 维度）。
     *
     * <p>与 {@code OpenApiBlockExceptionHandler} 负责的是**同一份契约**，只是入口不同
     * （那条路是适配器的 Interceptor，这条路是 Filter 里的手写埋点）——
     * 两处的输出必须一致，否则接入方会看到两种 429。
     */
    private void writeBlockResponse(HttpServletResponse response,
                                    com.alibaba.csp.sentinel.slots.block.BlockException e)
            throws IOException {
        ErrorCode code = ErrorCode.QPS_LIMIT_EXCEEDED;
        String dimension = "请求速率超限";
        String retryAfter = "1";

        // 日配额被拒时，异常自己带着"到明天还有多少秒"——那是 Retry-After 最准确的答案
        if (e instanceof com.newcoupon.infrastructure.ratelimit.DailyQuotaBlockException daily) {
            code = ErrorCode.DAILY_QUOTA_EXCEEDED;
            dimension = "日配额已用尽";
            retryAfter = String.valueOf(daily.retryAfterSeconds());
        }

        log.warn("SENTINEL BLOCK（过滤器层）：resource={} —— 按 {} 拒绝",
                com.newcoupon.infrastructure.ratelimit.TenantFlowRuleMapper.OPEN_API_RESOURCE, code.getCode());

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", retryAfter);
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(code, dimension + "（资源："
                        + com.newcoupon.infrastructure.ratelimit.TenantFlowRuleMapper.OPEN_API_RESOURCE + "）")));
    }

    private void exitTenantDimension(String appId) {
        com.alibaba.csp.sentinel.Entry entry = tenantDimensionEntry.get();
        if (entry != null) {
            entry.exit(1, appId);
            tenantDimensionEntry.remove();
        }
        // 与 enter 配对。不清会把 Context 留在 Tomcat 线程池的线程上，
        // 下一个请求会串味——与 RequestContext 必须 finally clear 是同一个道理。
        com.alibaba.csp.sentinel.context.ContextUtil.exit();
    }

    /** 这个调用链的名字（Sentinel 的 context name，与资源名不是一回事） */
    private static final String CONTEXT_NAME = "open-api";

    /** 当前请求的聚合资源 Entry（ThreadLocal：Entry/exit 必须同线程配对） */
    private static final ThreadLocal<com.alibaba.csp.sentinel.Entry> tenantDimensionEntry =
            new ThreadLocal<>();

    @SuppressWarnings("unused")
    private boolean withinQps(String appId, int registeredLimit, HttpServletResponse response) throws IOException {
        int limit = resolveLimit(appId, TenantConfigKey.RATE_LIMIT_QPS, registeredLimit);
        if (limit <= 0) {
            return true;
        }
        // **subject 只是"限谁"，不含时间**（2026-10-08 修正）：
        // 旧写法把"当前秒"拼进键里，等于让调用方替算法决定"窗口怎么切"——
        // 滑动窗口因此读不到"上一窗口"（它派生在基础键下面，而基础键每秒就换），
        // 配了滑动窗口的租户实际拿到的是退化的固定窗口。窗口怎么切是实现的职责。
        String subject = QPS_KEY_PREFIX + appId;
        if (acquireSafely(appId, subject, limit, Duration.ofSeconds(2), "tenant_qps")) {
            return true;
        }
        reject(response, ErrorCode.QPS_LIMIT_EXCEEDED, "1", "租户配额", "tenant_qps");   // 下一秒就是新窗口
        return false;
    }

    private boolean withinDailyQuota(String appId, int registeredLimit, HttpServletResponse response) throws IOException {
        int limit = resolveLimit(appId, TenantConfigKey.RATE_LIMIT_DAILY_QUOTA, registeredLimit);
        if (limit <= 0) {
            return true;
        }
        // 同样只传"限谁"：日配额的"当天"这个概念由窗口长度表达（到次日 0 点），
        // 不再是"键里拼日期、靠换键重置"。
        long secondsUntilTomorrow = Duration.between(LocalDateTime.now(),
                LocalDate.now().plusDays(1).atStartOfDay()).getSeconds();
        if (acquireSafely(appId, DAILY_KEY_PREFIX + appId, limit,
                Duration.ofSeconds(Math.max(1, secondsUntilTomorrow)), "tenant_daily")) {
            return true;
        }
        reject(response, ErrorCode.DAILY_QUOTA_EXCEEDED, String.valueOf(Math.max(1, secondsUntilTomorrow)), "日配额", "tenant_daily");
        return false;
    }

    /**
     * 平台级维度（IP / 接口）的限流检查。**任一超限即拒**，且错误信息里带上维度名——
     * 接入方看到 429 时能知道是自己打太快，还是这个接口被全平台限了。
     *
     * <p>（2026-10-08 注：这里原写着"用默认算法（滑动窗口）"——那个说法已经作废。
     * 现在只有一个限流实现（Bucket4j 令牌桶），不存在"平台级用哪种算法"这回事。）
     */
    private boolean withinPlatformDimension(String dimension, String metricDimension, String keyPrefix,
                                            int limit, boolean enabled,
                                            HttpServletResponse response) throws IOException {
        if (!enabled || limit <= 0) {
            return true;
        }
        String key = keyPrefix;   // 只传"限谁"，窗口交给算法
        boolean allowed;
        try {
            allowed = limiter.tryAcquire(key, limit, Duration.ofSeconds(2));
        } catch (RuntimeException e) {
            log.error("平台级限流检查失败（Redis 不可用），放行：dimension={} key={}", dimension, key, e);
            governanceMetrics.recordLimitFailOpen(metricDimension);
            return true;   // fail-open，与配额一致
        }
        if (allowed) {
            return true;
        }
        reject(response, ErrorCode.QPS_LIMIT_EXCEEDED, "1", dimension + "维度", metricDimension);
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
     * 执行一次限流检查；**fail-open**（配额是可用性防线，不因中间件故障误杀全量接入方）。
     *
     * <p>（2026-10-08 起不再"按租户配置挑算法"——那个配置项已删：调研核实业界没有
     * "固定窗口/滑动窗口/令牌桶三选一"这种东西，主流就是令牌桶。
     * 而它在本项目里还是坏的：见 {@code RateLimiter} 的类注释。）
     */
    private boolean acquireSafely(String appId, String key, int limit, Duration ttl,
                                  String metricDimension) {
        try {
            return limiter.tryAcquire(key, limit, ttl);
        } catch (RuntimeException e) {
            log.error("配额检查失败（Redis 不可用），放行：key={}", key, e);
            // **fail-open 必须可见**（S2 第 11 步）：限流是可用性防线，依赖故障时放行是对的，
            // 但那样仪表盘上会呈现「拒绝数为 0、一切正常」的假象——实际上防线已经失效。
            // 没有这条指标，「防线失效」与「流量本来就小」在监控上完全无法区分。
            governanceMetrics.recordLimitFailOpen(metricDimension);
            return true;
        }
    }

    private void reject(HttpServletResponse response, ErrorCode code, String retryAfterSeconds,
                        String dimension, String metricDimension) throws IOException {
        // 打点用 **ASCII 维度码**而不是响应里的中文文案：文案一改标签就断，
        // 而指标标签是长期契约（改了等于历史序列清零）。
        governanceMetrics.recordLimitRejected(metricDimension);
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // 429 必须告诉对方多久后重试——否则接入方只能瞎猜（或死循环重试，把限流变成自杀）
        response.setHeader(RETRY_AFTER, retryAfterSeconds);
        // 带上维度名：接入方看到 429 时能区分"我打太快"与"这个接口被全平台限了"
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, dimension)));
    }
}
