package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

/**
 * 一次"拒绝"的**对外契约映射**（迁移第 6 步，2026-10-08）。
 *
 * <p><b>为什么要把这件事单独抽出来</b>：限流被拒有**两个出口**——
 * <ol>
 *   <li>适配器的 {@code SentinelWebInterceptor} 埋点（Controller 层）：拒绝走
 *       {@link OpenApiBlockExceptionHandler}；</li>
 *   <li>我们自己的 {@code OpenApiQuotaFilter} 里的手写 {@code SphU.entry}（Filter 层）：
 *       它的异常**到不了** {@code @RestControllerAdvice}，必须自己写响应。</li>
 * </ol>
 * 两个出口各写一遍 → 迟早长出两种 429（"契约漂移"是接线型代码最常见的退化）。
 * 所以映射规则（业务码 / 维度文案 / Retry-After / 指标标签）与写响应的动作**都放在这里**，
 * 出口只剩一行调用。
 *
 * <p><b>指标也在这里打</b>：拒绝的计数只在一个地方发生，就不会出现"某条路径漏记"
 * 或"两条路径各记一次"。故障（fail-open）的打点则留在发生故障的那一处
 * （{@code RedisDailyQuotaChecker} / {@code RedisIpExactChecker}）——两者不是一回事：
 * 拒绝是结果，故障是原因。
 *
 * <p><b>四要素</b>（S1/S2 一路建立的对外契约，迁移不能把它弄丢）：
 * <pre>
 *   HTTP 429 + JSON 业务码 + Retry-After + 说明是哪个维度
 * </pre>
 *
 * <p><b>维度怎么判</b>：{@code BlockException} 有五个官方子类，但**不能只靠类型**——
 * 集群流控被限抛的同样是 {@code FlowException}。所以判据是"自定义异常类型 + 资源名"，
 * 并且文案里带上资源名，让接入方能区分「我打太快」与「这个接口被平台限了」。
 */
public final class BlockRejection {

    private static final Logger log = LoggerFactory.getLogger(BlockRejection.class);

    // ---- 指标标签：ASCII 维度码。**对 Prometheus 是长期契约**（改了等于历史序列清零），
    //      与 GovernanceMetrics 的类注释同源。响应里的中文文案可以改，这些不能。----
    static final String METRIC_TENANT_QPS = "tenant_qps";
    static final String METRIC_TENANT_DAILY = "tenant_daily";
    static final String METRIC_IP = "ip";
    static final String METRIC_RESOURCE = "resource";
    static final String METRIC_SYSTEM = "system";
    /** 兜底标签：出现了说明有我们没预料到的 BlockException 子类，值得排查 */
    static final String METRIC_OTHER = "other";

    /** 限流类拒绝建议的重试间隔（秒）：QPS 窗口是 1 秒，下一秒就是新窗口 */
    private static final String ONE_SECOND = "1";

    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private final ErrorCode code;
    private final String dimension;
    private final String metricDimension;
    private final String retryAfterSeconds;
    /** HTTP 状态码：限流类用 429；**系统过载用 503**（第 8 步改，见 {@link #of} 里的说明） */
    private final int httpStatus;

    private BlockRejection(ErrorCode code, String dimension,
                           String metricDimension, String retryAfterSeconds, int httpStatus) {
        this.code = code;
        this.dimension = dimension;
        this.metricDimension = metricDimension;
        this.retryAfterSeconds = retryAfterSeconds;
        this.httpStatus = httpStatus;
    }

    /** 非异常路径（如"限流器返回 false"）构造一个拒绝——与异常路径共用同一份契约 */
    public static BlockRejection of(ErrorCode code, String dimension,
                                    String metricDimension, String retryAfterSeconds) {
        return new BlockRejection(code, dimension, metricDimension, retryAfterSeconds, HTTP_TOO_MANY_REQUESTS);
    }

    /**
     * 把 Sentinel 抛出的拒绝翻译成本项目的契约。
     *
     * @param resourceName 被拒绝的资源名（Sentinel 的拒绝异常**不带完整规则信息**：
     *                     {@code BlockException} 不采集堆栈、{@code getRule()} 可能为 null，
     *                     所以资源名由调用方（适配器/过滤器）提供）
     */
    public static BlockRejection of(BlockException e, String resourceName) {
        if (e instanceof DailyQuotaBlockException daily) {
            // 日配额：**唯一一个 Retry-After 不是 1 秒的维度**（它要答"到明天还有多久"）
            return new BlockRejection(ErrorCode.DAILY_QUOTA_EXCEEDED, "日配额已用尽",
                    METRIC_TENANT_DAILY, String.valueOf(daily.retryAfterSeconds()), HTTP_TOO_MANY_REQUESTS);
        }
        if (e instanceof IpExactBlockException) {
            return new BlockRejection(ErrorCode.QPS_LIMIT_EXCEEDED, "IP 维度请求速率超限",
                    METRIC_IP, ONE_SECOND, HTTP_TOO_MANY_REQUESTS);
        }
        if (e instanceof SystemBlockException) {
            // 系统自适应保护触发：不是"谁打太快"，而是"这台机器自己扛不住了"。
            // 业务码用 5000（系统繁忙）而不是 6006（请求超限）；**状态码也改成 503**
            // （第 8 步）：429 的语义是"你打太快了，慢点再来"，503 才是"服务现在不可用"——
            // 接入方对两者的处置完全不同（前者退避后重试同一个实例，后者该认为整体不可用）。
            // 业务码本来就是按这个语义定的，状态码一直没跟上。
            return new BlockRejection(ErrorCode.SYSTEM_ERROR, "系统自适应保护",
                    METRIC_SYSTEM, ONE_SECOND, HTTP_SERVICE_UNAVAILABLE);
        }
        if (e instanceof FlowException) {
            // 租户维度与接口维度**抛的是同一个类型**（都是 FlowRule 命中），只能按资源名分：
            // 聚合资源 open_api 上挂的是"该租户跨所有接口的总量"，其余是"某个接口被平台限了"。
            // 集群模式下租户维度用的是"每租户一个资源名"（open_api:<appId>），所以用前缀判
            return resourceName != null
                    && resourceName.startsWith(TenantFlowRuleMapper.OPEN_API_RESOURCE)
                    ? new BlockRejection(ErrorCode.QPS_LIMIT_EXCEEDED, "租户请求速率超限",
                            METRIC_TENANT_QPS, ONE_SECOND, HTTP_TOO_MANY_REQUESTS)
                    // 非聚合资源的 FlowRule = 平台给"某个接口"设的上限（第 8 步从 Bucket4j 迁过来）。
                    // 指标标签仍是 resource（对 Prometheus 是长期契约，改名等于历史序列清零）
                    : new BlockRejection(ErrorCode.QPS_LIMIT_EXCEEDED, "接口维度请求速率超限",
                            METRIC_RESOURCE, ONE_SECOND, HTTP_TOO_MANY_REQUESTS);
        }
        // 其余子类（热点参数 / 熔断 / 授权）本项目暂时没有用到，但**不能让它落到默认处理器**——
        // 那又变回纯文本了。用类名当维度文案，并打上 other 标签，让"没预料到的东西"可被发现。
        return new BlockRejection(ErrorCode.QPS_LIMIT_EXCEEDED, e.getClass().getSimpleName(),
                METRIC_OTHER, ONE_SECOND, HTTP_TOO_MANY_REQUESTS);
    }

    /**
     * 写响应：**429 + JSON 业务码 + Retry-After + 维度文案**，并给拒绝计数打点。
     *
     * <p>之所以不直接返回 {@code Result} 让调用方自己写：这里要额外设 {@code Retry-After}
     * 响应头，而 {@code Result} 只描述响应体——两件事分开做更清楚。
     */
    public void writeTo(HttpServletResponse response, ObjectMapper objectMapper,
                        GovernanceMetrics metrics, String resourceName) throws IOException {
        // 打点用 ASCII 维度码而不是响应里的中文文案：文案一改标签就断，而指标标签是长期契约
        metrics.recordLimitRejected(metricDimension);

        log.warn("SENTINEL BLOCK：resource={} 维度={} 业务码={} Retry-After={}s",
                resourceName, metricDimension, code.getCode(), retryAfterSeconds);

        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 不声明字符集时 Servlet 默认 ISO-8859-1，手写的中文会变乱码
        response.setCharacterEncoding("UTF-8");
        // 429 必须告诉对方多久后重试——否则接入方只能瞎猜或死循环重试（把限流变成自杀）
        response.setHeader("Retry-After", retryAfterSeconds);
        // 带上维度 + 资源名：接入方据此区分「我打太快」与「这个接口被平台限了」，也便于排障
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(code, dimension + "（资源：" + resourceName + "）")));
    }
}
