package com.newcoupon.common.context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一请求上下文（S1 治理插槽①，2026-10-01）。
 *
 * <p><b>为什么要有它</b>：限流、熔断、监控、开关、审计、日志——这些治理设施都需要同一组
 * "这个请求是谁、打哪个资源、还剩多少时间预算"。没有统一上下文，每个设施各自从头解析一遍，
 * key 迟早对不上（限流按 A 算、审计按 B 记，对不上账）。
 *
 * <p><b>三个设计决定</b>：
 * <ol>
 *   <li><b>ThreadLocal 而不是方法参数</b>：从 Filter 一路传到领域层要穿过十几层签名，
 *       每层都加一个参数是灾难（而且领域层不该知道 HTTP 的存在）。TraceId 用 MDC 就是这个理由。</li>
 *   <li><b>{@link #current()} 返回 EMPTY 而不是 null</b>：定时任务/内部调用没有上下文，
 *       让调用方写一堆判空是噪音。EMPTY 的 writer 方法全部 no-op（见 {@code guard}）。</li>
 *   <li><b>必须在 finally 里 {@link #clear()}</b>：Tomcat 线程池复用线程，不清就把上一个请求的
 *       appId 带给下一个请求——与 MDC 同一个经典事故（TraceIdFilter 里已有同款注释）。</li>
 * </ol>
 *
 * <p><b>deadline（超时预算）在 S1 只透传不裁剪</b>：S2 会用它给 Redis/DB 调用设超时，
 * 目标是"快到期就别再做非必要工作"。现在留着位置并记进日志/审计，让将来的接入**零改动**。
 */
public final class RequestContext {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    /** 无上下文（定时任务、内部调用）时的占位。注意：它的所有 writer 都是 no-op */
    private static final RequestContext EMPTY =
            new RequestContext(null, null, Long.MAX_VALUE, false);

    private final String appId;
    private final String traceId;
    private final long deadlineMs;
    private final boolean deadlineExplicit;

    private Long userId;
    private String resource;
    private final Map<String, String> dimensions = new LinkedHashMap<>();

    public RequestContext(String appId, String traceId, long deadlineMs, boolean deadlineExplicit) {
        this.appId = appId;
        this.traceId = traceId;
        this.deadlineMs = deadlineMs;
        this.deadlineExplicit = deadlineExplicit;
    }

    public static RequestContext current() {
        RequestContext ctx = HOLDER.get();
        return ctx == null ? EMPTY : ctx;
    }

    public static void set(RequestContext ctx) {
        HOLDER.set(ctx);
    }

    /** 必须在 finally 调用——线程池复用线程，不清会把上个请求的身份带给下个请求 */
    public static void clear() {
        HOLDER.remove();
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 签名认证过的接入方（可信身份）。内部调用路径为 null */
    public String appId() {
        return appId;
    }

    public String traceId() {
        return traceId;
    }

    public Long userId() {
        return userId;
    }

    public boolean deadlineExplicit() {
        return deadlineExplicit;
    }

    /** 剩余预算（毫秒）；无预算时返回 {@link Long#MAX_VALUE} */
    public long remainingMs() {
        return deadlineMs == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineMs - System.currentTimeMillis();
    }

    public boolean expired() {
        return remainingMs() <= 0;
    }

    /**
     * 资源名：{@code 接口[:维度[:值]]}，例 {@code coupons/receive:template:2001}。
     *
     * <p>限流 / 熔断 / 监控 / 开关**共用这一套 key**——所以维度是**有序**追加的
     * （LinkedHashMap），保证同一个资源每次拼出来完全一样。
     */
    public String resourceName() {
        if (resource == null) {
            return null;
        }
        if (dimensions.isEmpty()) {
            return resource;
        }
        StringBuilder sb = new StringBuilder(resource);
        dimensions.forEach((k, v) -> sb.append(':').append(k).append(':').append(v));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 写（EMPTY 上全部 no-op）
    // ------------------------------------------------------------------

    /** 业务用户：由应用服务在解析出 userId 后补写（Filter 阶段拿不到——它在 body 里） */
    public void user(Long id) {
        if (guard()) {
            this.userId = id;
        }
    }

    public void resource(String name) {
        if (guard()) {
            this.resource = name;
        }
    }

    /** 补一个资源维度（如 {@code template=2001}）→ 资源名变成 {@code receive:template:2001} */
    public void dimension(String key, Object value) {
        if (guard() && key != null && value != null) {
            dimensions.put(key, String.valueOf(value));
        }
    }

    /** 是否是真实上下文（EMPTY 返回 false）。EMPTY 上的写入是 no-op，避免污染这个共享单例 */
    private boolean guard() {
        return this != EMPTY;
    }

    @Override
    public String toString() {
        return "RequestContext{appId=" + appId + ", userId=" + userId
                + ", resource=" + resourceName() + ", remainingMs=" + remainingMs()
                + ", deadlineExplicit=" + deadlineExplicit + '}';
    }
}
