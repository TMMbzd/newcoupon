package com.newcoupon.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.service.CachedOpenAppReader;
import com.newcoupon.application.service.CachedOpenAppReader.AppAuth;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.common.crypto.OpenApiAuthHeader;
import com.newcoupon.common.crypto.OpenApiSignature;
import com.newcoupon.infrastructure.persistence.OpenApiAuditRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 开放面认证过滤器（S1 第 4 步）——只拦 {@code /open/**}。
 *
 * <p><b>六步认证链</b>（任一失败即拒，且**每一步有自己的错误码**——排查时能直接定位到哪一环）：
 * <pre>
 *   1. 版本前缀      /open/v1/** 之外 → 6010（400）
 *   2. 解析签名头    → 6001（401）
 *   3. 查应用+密钥   → 6002（401）
 *   4. 时间窗 ±5min  → 6004（401）
 *   5. 验签          → 6003（401）
 *   6. nonce 防重放  → 6005（401）/ Redis 故障 → 5000（503）
 * </pre>
 *
 * <p><b>两个顺序问题，顺序错了就是安全漏洞</b>：
 * <ol>
 *   <li><b>先验签、再写 nonce</b>。反过来的话，攻击者只要拿<b>垃圾签名</b>反复请求，就能：
 *       ① 把 Redis 写爆；② <b>预占</b>合法客户端接下来要用的 nonce——正常请求反被判"重放"。
 *       检查顺序本身就是安全设计。</li>
 *   <li><b>时间窗在验签之前</b>。过期请求不必浪费一次 HMAC 计算（HMAC 是这条链上最贵的操作），
 *       而且"时间戳错了"比"签名错了"更常见（客户端时钟漂移），先报更具体的错。</li>
 * </ol>
 *
 * <p><b>为什么 nonce 的 Redis 故障要 fail-closed，而 IP 频控是 fail-open</b>（同一个项目里
 * 两种相反的裁决，值得对照着看）：频控是<b>可用性</b>防线——Redis 挂了还误杀全量，代价大于
 * 短暂失效；nonce 是<b>安全</b>防线——Redis 挂了就只剩时间窗，5 分钟内重放畅通无阻。
 * 而且 Redis 挂时库存闸门本来就不可用，fail-closed 不会额外降低可用性。
 * <b>注意错误归因</b>：Redis 故障返回 5000（系统繁忙，可重试），<b>绝不能</b>返回 6005——
 * 那等于告诉接入方"你在重放"，把运维故障说成对方的问题。
 *
 * <p>失败响应统一走 {@link Result}（不手写 JSON 字符串）：与业务错误同一个响应体形状，
 * 顺带避开 L43 那类"手写体忘了声明 UTF-8 变乱码"的坑。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)   // TraceIdFilter(+0) 之后，RiskControlFilter(+10) 之前
public class OpenApiAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiAuthFilter.class);

    private static final String OPEN_PREFIX = "/open/";
    private static final String V1_PREFIX = "/open/v1/";
    private static final String AUTH_HEADER = "Authorization";
    private static final String TIMEOUT_HEADER = "X-Timeout-Ms";
    private static final String NONCE_KEY_PREFIX = "c:open:nonce:";

    /** 时间窗 ±5 分钟 */
    private static final long WINDOW_MS = 5 * 60 * 1000L;
    /** nonce 保留 2 倍窗口（覆盖窗口边界：窗口过期的请求不会再被接受，nonce 也就不必再留） */
    private static final Duration NONCE_TTL = Duration.ofSeconds(600);
    /** 未带 X-Timeout-Ms 时的服务端默认预算（S2 会用它裁剪下游超时，S1 只透传） */
    private static final long DEFAULT_BUDGET_MS = 3_000;
    private static final long MAX_BUDGET_MS = 30_000;

    private final CachedOpenAppReader appReader;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final OpenApiAuditRecorder auditRecorder;

    public OpenApiAuthFilter(CachedOpenAppReader appReader,
                             StringRedisTemplate redis,
                             ObjectMapper objectMapper,
                             OpenApiAuditRecorder auditRecorder) {
        this.appReader = appReader;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.auditRecorder = auditRecorder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(OPEN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String path = request.getServletPath();
        String declaredAppId = null;     // 头里声明的 appId（验签前不可信，仅供审计"谁在敲门"）
        boolean verified = false;
        Integer rejectedCode = null;     // 被接入层拦下时的错误码（审计用）

        try {
            // 第 1 步：版本（未实现的版本直接拒——404 无法区分"版本不存在"和"接口写错"）
            if (!path.startsWith(V1_PREFIX)) {
                rejectedCode = reject(response, 400, ErrorCode.API_VERSION_UNSUPPORTED);
                return;
            }

            // body 一次性读入内存：验签要算它的哈希，而 Servlet 的流只能读一次（读完了 Controller 就没得读）
            CachedBodyRequestWrapper wrapped = new CachedBodyRequestWrapper(request);

            // 第 2 步：签名头
            OpenApiAuthHeader header = OpenApiAuthHeader.parse(wrapped.getHeader(AUTH_HEADER));
            if (header == null) {
                rejectedCode = reject(response, 401, ErrorCode.SIGN_MISSING);
                return;
            }
            declaredAppId = header.appId();

            // 第 3 步：应用与密钥（走本地缓存，命中时不触库；停用视为不存在）
            Optional<AppAuth> found = appReader.find(header.appId());
            if (found.isEmpty() || !found.get().enabled() || !found.get().hasUsableSecret()) {
                log.warn("OPEN-API 拒绝：应用不可用 appId={} path={}", header.appId(), path);
                rejectedCode = reject(response, 401, ErrorCode.APP_INVALID);
                return;
            }
            AppAuth app = found.get();

            // 第 4 步：时间窗
            long now = System.currentTimeMillis();
            if (Math.abs(now - header.timestamp()) > WINDOW_MS) {
                rejectedCode = reject(response, 401, ErrorCode.TIMESTAMP_OUT_OF_WINDOW);
                return;
            }

            // 第 5 步：验签（对每一把可用密钥试——轮换期内新旧并存）
            String canonical = OpenApiSignature.canonicalString(
                    request.getMethod(),
                    path,
                    OpenApiSignature.canonicalQuery(request.getQueryString()),
                    OpenApiSignature.bodySha256(wrapped.cachedBody()),
                    header.appId(), header.timestamp(), header.nonce());
            boolean signed = app.secrets().stream()
                    .anyMatch(secret -> OpenApiSignature.verify(secret, canonical, header.signature()));
            if (!signed) {
                log.warn("OPEN-API 拒绝：签名不匹配 appId={} path={}", header.appId(), path);
                rejectedCode = reject(response, 401, ErrorCode.SIGN_MISMATCH);
                return;
            }

            // 第 6 步：nonce 防重放（必须在验签之后！）
            Integer nonceReject = consumeNonce(header.appId(), header.nonce(), response);
            if (nonceReject != null) {
                rejectedCode = nonceReject;
                return;
            }

            // 认证通过：建立请求上下文
            verified = true;
            RequestContext ctx = new RequestContext(
                    header.appId(), MDC.get(TraceIdFilter.TRACE_ID), deadline(request), hasTimeoutHeader(request));
            ctx.resource(path.substring(V1_PREFIX.length()));   // 如 coupons/receive（维度由应用服务补）
            RequestContext.set(ctx);

            chain.doFilter(wrapped, response);
        } finally {
            // 收尾三件事，**成功失败都会执行**：审计 → 访问日志 → 清上下文
            recordAudit(request, response, declaredAppId, verified, rejectedCode, start);
            if (RequestContext.current().appId() != null) {
                exposeContext(RequestContext.current(), request, response);
            }
            RequestContext.clear();   // 线程池复用，必须清（与 MDC 同一个道理）
        }
    }

    /**
     * 调用审计：一行 = 一次调用。
     *
     * <p><b>成功与失败都落</b>——失败调用往往是排查里最有价值的那一半
     * （谁在敲门、敲了多少次、是签名错还是配额超）。未验签通过时记的是**声明值**，
     * 由 {@code verified} 列区分"谁在敲门"与"谁进了门"。
     */
    private void recordAudit(HttpServletRequest request, HttpServletResponse response,
                             String declaredAppId, boolean verified, Integer rejectedCode, long start) {
        RequestContext ctx = RequestContext.current();
        auditRecorder.record(new OpenApiAuditRecorder.AuditEntry(
                MDC.get(TraceIdFilter.TRACE_ID),
                declaredAppId,
                verified,
                ctx.resourceName(),
                request.getMethod(),
                request.getServletPath(),
                response.getStatus(),
                verified ? ctx.resultCode() : rejectedCode,
                (int) (System.currentTimeMillis() - start),
                RiskControlFilter.clientIp(request),
                ctx.userId()));
    }

    /**
     * 请求收尾：打一行访问日志（一行 = 一次调用）。
     *
     * <p><b>为什么在 finally 而不是拦截器的 postHandle</b>：handler 抛异常时 Spring 不会调 postHandle
     * （异常直接走 HandlerExceptionResolver 出响应），而业务失败恰恰最需要看到资源名。
     * finally 里**成功失败都会执行**。
     *
     * <p><b>曾经想在这里回写 {@code X-Request-Resource} 响应头，实测做不到</b>：
     * Tomcat 在消息转换器写完响应体时就 flush 了 buffer，filter chain 返回时响应**已经提交**——
     * 按 Servlet 规范，提交之后设置响应头无效（实测读到的是 null）。这是"想当然会生效"的典型：
     * 真正能用的时机（interceptor 的 postHandle）又恰好在业务抛异常时被跳过。
     * <b>结论：把"我的请求算到哪个资源"交给 traceId 查日志</b>（审计接口在 S1 第 10 步落地）。
     *
     * <p>此刻 resource 已含应用服务补的维度（如 {@code coupons/receive:template:2001}）——
     * 这是"治理插槽真的接上了"的可见证据。
     */
    private void exposeContext(RequestContext ctx, HttpServletRequest request, HttpServletResponse response) {
        log.info("open-api: appId={} {} {} resource={} status={} remainingMs={}",
                ctx.appId(), request.getMethod(), request.getServletPath(),
                ctx.resourceName(), response.getStatus(), ctx.remainingMs());
    }

    /**
     * 消费 nonce。
     *
     * @return {@code null}=首次使用（放行）；否则返回已写好的错误码（重放 或 Redis 故障）
     */
    private Integer consumeNonce(String appId, String nonce, HttpServletResponse response) throws IOException {
        String key = NONCE_KEY_PREFIX + appId + ":" + nonce;
        try {
            Boolean fresh = redis.opsForValue().setIfAbsent(key, "1", NONCE_TTL);
            if (!Boolean.TRUE.equals(fresh)) {
                log.warn("OPEN-API 拒绝：nonce 重放 appId={} nonce={}", appId, nonce);
                return reject(response, 401, ErrorCode.NONCE_REPLAYED);
            }
            return null;   // 通过
        } catch (RuntimeException e) {
            // fail-closed：nonce 是安全防线。但归因必须是"系统故障"而非"你在重放"
            log.error("OPEN-API nonce 检查失败（Redis 不可用）——拒绝请求 appId={}", appId, e);
            return reject(response, 503, ErrorCode.SYSTEM_ERROR);
        }
    }

    private static boolean hasTimeoutHeader(HttpServletRequest request) {
        String raw = request.getHeader(TIMEOUT_HEADER);
        return raw != null && !raw.isBlank();
    }

    /** 超时预算：请求头 X-Timeout-Ms（越界/非法视为未带）→ deadline = now + 预算 */
    private static long deadline(HttpServletRequest request) {
        long budget = DEFAULT_BUDGET_MS;
        String raw = request.getHeader(TIMEOUT_HEADER);
        if (raw != null && !raw.isBlank()) {
            try {
                long parsed = Long.parseLong(raw.trim());
                if (parsed > 0 && parsed <= MAX_BUDGET_MS) {
                    budget = parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法值按未带处理：预算这东西宁可用默认值，也不要因为一个坏头拒绝请求
            }
        }
        return System.currentTimeMillis() + budget;
    }

    /** @return 该错误码——调用方带回去记进审计（接入层的拒绝也要留痕） */
    private int reject(HttpServletResponse response, int httpStatus, ErrorCode code) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");   // L43：不声明则中文提示变乱码
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code)));
        return code.getCode();
    }
}
