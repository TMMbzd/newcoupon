package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 适配器埋点路径上的拒绝处理器（迁移第 4 步建立，第 6 步收敛）。
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
 * <p><b>本类现在只剩"接线"</b>：映射规则与写响应都在 {@link BlockRejection}——
 * 本项目有**两个**拒绝出口（适配器的拦截器 + 过滤器里手写的 {@code SphU.entry}），
 * 契约必须只有一份实现，否则迟早长出两种 429。
 */
public class OpenApiBlockExceptionHandler implements BlockExceptionHandler {

    private final ObjectMapper objectMapper;
    private final GovernanceMetrics metrics;

    public OpenApiBlockExceptionHandler(ObjectMapper objectMapper, GovernanceMetrics metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       String resourceName, BlockException e) throws IOException {
        // 资源名由适配器给出（BlockException 自己不完整：不采集堆栈、getRule() 可能为 null）
        BlockRejection.of(e, resourceName).writeTo(response, objectMapper, metrics, resourceName);
    }
}
