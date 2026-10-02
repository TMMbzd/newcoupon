package com.newcoupon.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.common.crypto.OpenApiSignature;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 接入层幂等过滤器（S1 第 6 步）——只拦 {@code /open/**} 的 <b>POST</b>（写操作）。
 *
 * <p><b>它保证什么</b>：同一个 {@code X-Client-Token} + 同样的参数 → <b>返回同一个响应体</b>
 * （逐字节一致），并且业务只执行一次。这是**面向接入方的契约**：接入方的超时重试逻辑可以很简单
 * ——"拿到的响应就是唯一的事实"。
 *
 * <p><b>三层幂等的分工</b>（不是冗余，是三层不同的保证）：
 * <pre>
 *   契约层  X-Client-Token   同 token 重放 → 同一个响应体（本类）
 *   业务层  requestId        同一领取意图 → 不重复发券（DB uk_idem，已有）
 *   资源层  usageNo          同一张券的锁/核销/释放 → 单次生效（已有）
 * </pre>
 * 本层挂了（Redis 故障）也**不会丢数据**——下面两层还站着。所以它可以 **fail-open**：让请求
 * 照常走业务，由业务层保证不重复发券。<b>幂等是增强，不是门槛。</b>（对照 nonce 的 fail-closed：
 * 那是安全防线，性质不同。）
 *
 * <p><b>三个会静默失效的坑</b>（设计文档 §4.6 预警，用例逐个钉住）：
 * <ol>
 *   <li><b>请求体必须先读进内存</b>：{@code ContentCachingRequestWrapper.getContentAsByteArray()}
 *       在 body 尚未被读取时返回空数组——那样指纹恒为"空 body 的哈希"，"同 token 异参"永远判不出来。
 *       本类用的是上游 {@link CachedBodyRequestWrapper}（一次性读、可重放），天然规避。</li>
 *   <li><b>响应包装器必须 {@code copyBodyToResponse()}</b>：否则响应被包装器吞掉，客户端收到空 body。
 *       放在 finally 里——业务抛异常时也要把已写的内容吐出去。</li>
 *   <li><b>重放命中时绝不能调 {@code chain.doFilter}</b>：那等于把业务又执行一遍，幂等就成了摆设。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 7)   // 认证(+5) → 配额(+6) → 幂等(+7)
public class OpenApiIdempotentFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiIdempotentFilter.class);

    private static final String OPEN_PREFIX = "/open/";
    public static final String CLIENT_TOKEN_HEADER = "X-Client-Token";
    /** 重放命中时告诉接入方"这是重放结果，不是你这次执行产生的" */
    public static final String REPLAY_HEADER = "X-Idempotent-Replay";

    private static final String KEY_PREFIX = "c:open:idem:";
    private static final String PENDING = "PENDING";
    /** 处理中窗口：超过它说明第一次执行凶多吉少（进程可能已死），允许重试 */
    private static final Duration PENDING_TTL = Duration.ofSeconds(60);
    /** 快照保留 24 小时：接入方的重试窗口远小于此 */
    private static final Duration SNAPSHOT_TTL = Duration.ofHours(24);
    /** 单条快照上限：超过就不缓存（防止大响应把 Redis 撑成"大 key 仓库"） */
    private static final int MAX_SNAPSHOT_BYTES = 64 * 1024;
    private static final Pattern CLIENT_TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public OpenApiIdempotentFilter(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(OPEN_PREFIX)
                || !"POST".equals(request.getMethod());   // 读操作天然幂等，不参与
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String clientToken = request.getHeader(CLIENT_TOKEN_HEADER);
        if (clientToken == null || !CLIENT_TOKEN.matcher(clientToken).matches()) {
            reject(response, 400, ErrorCode.CLIENT_TOKEN_MISSING);
            return;
        }
        String appId = RequestContext.current().appId();
        if (appId == null) {
            // 认证过滤器应当已经拦下无身份的请求；走到这里说明接线错了，放行走业务（不要静默吞请求）
            log.error("幂等过滤器在没有 appId 的请求上执行：path={}", request.getServletPath());
            chain.doFilter(request, response);
            return;
        }

        String key = KEY_PREFIX + appId + ":" + clientToken;
        String fingerprint = fingerprint(request);

        String existing = safeGet(key);
        if (existing == null) {
            Boolean acquired = safeSetIfAbsent(key);
            if (acquired == null) {                      // Redis 不可用 → fail-open
                chain.doFilter(request, response);
                return;
            }
            if (!Boolean.TRUE.equals(acquired)) {
                reject(response, 409, ErrorCode.CLIENT_TOKEN_PROCESSING);
                return;
            }
        } else if (PENDING.equals(existing)) {
            reject(response, 409, ErrorCode.CLIENT_TOKEN_PROCESSING);
            return;
        } else {
            replay(response, existing, fingerprint);
            return;                                       // 坑 3：绝不调 chain
        }

        // 抢到了执行权：包装响应以便完成后把结果存成快照
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
        } finally {
            saveSnapshot(key, wrapped, fingerprint);
            wrapped.copyBodyToResponse();                 // 坑 2：不调则客户端收到空 body
        }
    }

    // ------------------------------------------------------------------
    // 快照
    // ------------------------------------------------------------------

    private void saveSnapshot(String key, ContentCachingResponseWrapper response, String fingerprint) {
        try {
            byte[] body = response.getContentAsByteArray();
            if (body.length == 0 || body.length > MAX_SNAPSHOT_BYTES) {
                log.warn("幂等快照未写入（body 长度 {}）：key={}", body.length, key);
                return;
            }
            String text = new String(body, StandardCharsets.UTF_8);
            int code = objectMapper.readTree(text).path("code").asInt(-1);
            if (response.getStatus() >= 500 || (code >= 5000 && code < 6000)) {
                // "系统繁忙/降级"是**这次没成、下次可能成**，缓存它等于把一次抖动变成 24 小时的确定性失败。
                // 保留 PENDING（60 秒后自然过期）→ 期间重试得到 6009，之后可重试。
                return;
            }
            redis.opsForValue().set(key,
                    objectMapper.writeValueAsString(new Snapshot(fingerprint, response.getStatus(), text)),
                    SNAPSHOT_TTL);
        } catch (Exception e) {
            // 存快照失败不能让已经成功的业务变成失败（用户已经拿到券了）
            log.error("幂等快照写入失败：key={}", key, e);
        }
    }

    private void replay(HttpServletResponse response, String snapshotJson, String fingerprint) throws IOException {
        Snapshot snapshot;
        try {
            snapshot = objectMapper.readValue(snapshotJson, Snapshot.class);
        } catch (Exception e) {
            log.error("幂等快照损坏，按冲突处理", e);
            reject(response, 409, ErrorCode.CLIENT_TOKEN_CONFLICT);
            return;
        }
        if (!snapshot.fingerprint().equals(fingerprint)) {
            reject(response, 409, ErrorCode.CLIENT_TOKEN_CONFLICT);
            return;
        }
        response.setStatus(snapshot.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(REPLAY_HEADER, "true");
        response.getWriter().write(snapshot.body());
    }

    /**
     * 参数指纹：body 哈希 + 方法 + 路径。
     *
     * <p>为什么复用 body 的 SHA-256 而不是把 body 原文拼进指纹：body 可能很长，
     * 哈希是定长的；而且它已经把"参数有没有变"这个问题回答完了（改一个字节哈希就变）。
     * 方法/路径也要进指纹——同一个 token 用在不同接口上属于误用，必须判冲突而不是回放。
     */
    private static String fingerprint(HttpServletRequest request) throws IOException {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());   // 坑 1：先读进内存
        String raw = OpenApiSignature.bodySha256(body) + "\n"
                + request.getMethod() + "\n" + request.getServletPath();
        return OpenApiSignature.bodySha256(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @return 值；Redis 不可用返回 null（调用方按 fail-open 处理） */
    private String safeGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.error("幂等键读取失败（Redis 不可用），放行由业务层幂等兜底：key={}", key, e);
            return null;
        }
    }

    /** @return true=抢到；false=别人持有；null=Redis 不可用（fail-open） */
    private Boolean safeSetIfAbsent(String key) {
        try {
            return redis.opsForValue().setIfAbsent(key, PENDING, PENDING_TTL);
        } catch (RuntimeException e) {
            log.error("幂等键占位失败（Redis 不可用），放行由业务层幂等兜底：key={}", key, e);
            return null;
        }
    }

    private void reject(HttpServletResponse response, int httpStatus, ErrorCode code) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code)));
    }

    /** 快照：指纹 + HTTP 状态 + 响应体原文（回放要逐字节一致） */
    record Snapshot(String fingerprint, int status, String body) {
    }
}
