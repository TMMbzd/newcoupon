package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

/**
 * 把 Sentinel 的拒绝改写成**本项目的契约**（迁移第 4 步，2026-10-08）。
 *
 * <p><b>为什么必须自己写</b>：官方默认处理器只回
 * {@code 429 + 纯文本 "Blocked by Sentinel (flow limiting)"}——
 * 没有 JSON、没有业务码、没有 Retry-After、也没说是哪一维度。
 * 而本项目的契约是四要素齐全，接入方靠它们做重试决策与排障。
 *
 * <p><b>官方文档与代码不一致的一个坑</b>：v6x 适配器的 {@code SentinelWebMvcConfig}
 * 里 {@code blockExceptionHandler} 的默认值就是 {@code DefaultBlockExceptionHandler}，
 * 所以"不改就用默认"是它的实际行为——必须显式设置（见 {@code SentinelWebConfig}）。
 *
 * <p><b>维度怎么判</b>：{@link BlockException} 有五个子类，但**不能只靠类型**——
 * 比如集群流控被限抛的同样是 {@link FlowException}。所以这里按
 * "异常类型 + 资源名"两样一起判，并在文案里带上资源名，让接入方能区分。
 *
 * <p><b>为什么不用 {@code Result} 的工厂直接写</b>：这里要额外设 {@code Retry-After}
 * 响应头，而 {@code Result} 只描述响应体——两件事分开做更清楚。
 */
public class OpenApiBlockExceptionHandler implements BlockExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OpenApiBlockExceptionHandler.class);

    /** 限流类拒绝建议的重试间隔（秒）——与迁移前 {@code OpenApiQuotaFilter} 的取值一致 */
    private static final String RETRY_AFTER_SECONDS = "1";

    private final ObjectMapper objectMapper;

    public OpenApiBlockExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       String resourceName, BlockException e) throws IOException {
        ErrorCode code;
        String dimension;
        String retryAfter;

        if (e instanceof SystemBlockException) {
            // 系统自适应保护（BBR）触发：不是"谁打太快"，而是"这台机器自己扛不住了"。
            // 用 5000（系统繁忙）而不是 6006（请求超限）——前者的语义更准，也更能指导对方退避。
            code = ErrorCode.SYSTEM_ERROR;
            dimension = "系统自适应保护";
            retryAfter = RETRY_AFTER_SECONDS;
        } else if (e instanceof FlowException) {
            // 限流。注意：**集群流控被限抛的也是它**，所以文案里带上资源名以便区分。
            code = ErrorCode.QPS_LIMIT_EXCEEDED;
            dimension = "请求速率超限";
            retryAfter = RETRY_AFTER_SECONDS;
        } else {
            // 其余子类（热点参数 / 熔断 / 授权）本项目暂时没有用到，
            // 但**不能让它落到默认处理器**——否则又变回纯文本，契约就破了。
            code = ErrorCode.QPS_LIMIT_EXCEEDED;
            dimension = e.getClass().getSimpleName();
            retryAfter = RETRY_AFTER_SECONDS;
        }

        log.warn("SENTINEL BLOCK：resource={} rule={} —— 按 {} 拒绝",
                resourceName, dimension, code.getCode());

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 不声明字符集时 Servlet 默认 ISO-8859-1，手写的中文会变乱码
        // （本项目在风控过滤器上踩过一次，见 L43）
        response.setCharacterEncoding("UTF-8");
        // 429 必须告诉对方多久后重试——否则接入方只能瞎猜或死循环重试
        response.setHeader("Retry-After", retryAfter);
        // 说明维度 + 资源名：接入方据此区分「我打太快」与「这个接口被平台限了」
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(code, dimension + "（资源：" + resourceName + "）")));
    }
}
