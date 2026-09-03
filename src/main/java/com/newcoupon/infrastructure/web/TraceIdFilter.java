package com.newcoupon.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路追踪过滤器：为每个 HTTP 请求生成/透传 traceId，写入 MDC 与响应头。
 *
 * <p>解决什么问题：线上出问题时，你只有两个信息——"用户说他领券失败了"和一个时间点。
 * 没有 traceId，你要在海量并发日志里人肉拼凑"这几行是同一个请求的"；
 * 有了 traceId，grep 一下就拿到这条请求的完整生命周期日志。
 *
 * <p>配套行为：
 * <ul>
 *   <li>上游若带 X-Trace-Id 则透传（跨服务串联的前提），否则生成新的；</li>
 *   <li>回写到响应头 X-Trace-Id，用户报障时让客服直接要这个号；</li>
 *   <li>MDC 是 ThreadLocal 实现——必须在 finally 里清掉，否则线程池复用会串日志（经典事故）。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)   // 比所有业务 Filter 都早执行
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);   // 线程池复用，必须清理，否则下个请求串到上个 traceId
        }
    }
}
