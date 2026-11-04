package com.newcoupon.infrastructure.web;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：所有异常在这里收敛为统一 Result，Controller/Service 里不写 try-catch 样板。
 *
 * <p>两条铁律（大厂接口安全基线）：
 * <ul>
 *   <li>堆栈只进日志，绝不进响应——堆栈包含类名/行号/SQL片段，是给攻击者的免费情报；</li>
 *   <li>预期内(BizException→WARN)与预期外(Exception→ERROR)分开——告警系统只对 ERROR 响铃，
 *       否则业务失败刷屏会把真事故淹没。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务失败：预期内，WARN 级别（不触发告警响铃）。
     *
     * <p><b>S1（2026-10-02）：6xxx 接入域的错误要映射成真实的 HTTP 状态码。</b>
     * 其余段（1~5xxx）保持"HTTP 200 + 业务码"的老约定不动——那是全站既有契约，
     * 改它等于把所有调用方的错误处理推倒重来。
     *
     * <p>为什么 6xxx 要特殊：它表示"接入层就拦下了"，语义上属于 HTTP 层面的事
     * （越权=403、冲突=409、配额=429）。接入方用标准 HTTP 客户端时，
     * 靠状态码分流比解析业务码更自然（重试中间件、网关规则也都按状态码配）。
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, jakarta.servlet.http.HttpServletResponse response) {
        log.warn("biz error: {}", e.getMessage());
        int httpStatus = httpStatusOf(e.getErrorCode());
        if (httpStatus != 200) {
            response.setStatus(httpStatus);
        }
        // 429 必须带 Retry-After——否则接入方只能瞎猜或死循环重试。
        //
        // 注意分工（第 6 步澄清）：**限流被拒不走这里**。Sentinel 的拒绝抛的是
        // BlockException 的子类，而配额过滤器在 Filter 层、它的异常到不了本类
        // （Filter 的异常交给 Servlet 容器）——那条路的响应由 `BlockRejection` 写。
        // 本类只负责"业务代码里抛出的 6xxx BizException"（如接口拦截器的越权、版本不符）。
        // 下面的 6007 分支是**防御性**的：目前没有代码路径会带它走到这里，但真出现时
        // "多久后重试"的正确答案仍然是"到明天"。
        if (httpStatus == 429 && response.getHeader("Retry-After") == null) {
            response.setHeader("Retry-After", retryAfterOf(e.getErrorCode()));
        }
        return Result.fail(e.getErrorCode(), detailOf(e));
    }

    /**
     * 429 的 {@code Retry-After}：**告诉对方多久后重试**。
     *
     * <p>两种 429 的答案不同：请求速率超限（6006）下一秒就是新窗口，所以是 1 秒；
     * 日配额用尽（6007）要等到**次日 0 点**——给出这个准确的秒数，接入方才不用猜。
     */
    private static String retryAfterOf(ErrorCode code) {
        if (code == ErrorCode.DAILY_QUOTA_EXCEEDED) {
            return String.valueOf(Math.max(1, java.time.Duration.between(
                    java.time.LocalDateTime.now(),
                    java.time.LocalDate.now().plusDays(1).atStartOfDay()).getSeconds()));
        }
        return "1";
    }

    /** 6xxx 接入域 → HTTP 状态（与 docs/S1-开放接入层-需求与设计-2026-10-01.md §4.10 的表一致） */
    private static int httpStatusOf(ErrorCode code) {
        int c = code.getCode();
        if (c < 6000 || c >= 7000) {
            return 200;                      // 非接入域：维持既有约定
        }
        return switch (c) {
            case 6006, 6007 -> 429;          // 配额
            case 6008, 6009 -> 409;          // 幂等冲突
            case 6011 -> 403;                // 越权
            case 6013 -> 403;                // 租户生命周期不可调用（V6 2B）：身份是真的，是平台不让调
            case 6010, 6012 -> 400;          // 版本 / 缺 clientToken
            default -> 401;                  // 6001~6005：签名与身份
        };
    }

    /**
     * 方法安全的拒绝（V6 2A）：@PreAuthorize 不通过抛的 AccessDeniedException 必须**原样上抛**，
     * 让安全链的 ExceptionTranslationFilter 决定 401（匿名）还是 403（权限不足）。
     *
     * <p>为什么必须显式兜这一手：下面的兜底 handler 捕 Exception——不重抛的话，
     * 拒绝会被吞成"HTTP 200 + 5000 系统繁忙"，前端既看不到 403，监控里还多了一批假系统错误。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public void handleAccessDenied(org.springframework.security.access.AccessDeniedException e) {
        throw e;   // 交给安全链出正确的 401/403（REST 场景无登录页重定向）
    }

    /**
     * 路径不存在（V6 2C 清偿 L80）：Spring 6.1 起未匹配路径抛 NoResourceFoundException，
     * 此前被下面的兜底 handler 收敛成 HTTP 200 + 5000——404 语义丢失，每次探测请求
     * 还刷一条 ERROR 假告警（告警系统对 ERROR 响铃，路径探测是常态噪音）。
     *
     * <p>WARN 而非 ERROR：与 405 同一立场——"请求打错了地方"是调用方的常态，
     * 不是系统事故。响应给真实 404 + Result 形状（1002）。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e,
            jakarta.servlet.http.HttpServletResponse response) {
        log.warn("not found: {}", e.getResourcePath());
        response.setStatus(404);
        return Result.fail(ErrorCode.RESOURCE_NOT_FOUND, "路径不存在: " + e.getResourcePath());
    }

    /**
     * HTTP 方法不支持：例如对只允许 POST 的财务查询发 GET。
     * 不落到兜底 5000——那是"系统异常"，会误导排障；405 才是准确的。
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotAllowed(
            org.springframework.web.HttpRequestMethodNotSupportedException e,
            jakarta.servlet.http.HttpServletResponse response) {
        log.warn("method not allowed: {}", e.getMessage());
        response.setStatus(405);
        return Result.fail(ErrorCode.PARAM_ERROR, "不支持的 HTTP 方法: " + e.getMethod());
    }

    /**
     * 参数校验失败：JSR-303 注解校验不通过时，把所有字段错误拼出来一次性返回。
     *
     * <p><b>HTTP 400（V6 2C 审查 m5 起）</b>：与下方 m4 的两个 handler 同一哲学——
     * "请求没构造对"是调用方手误，不是系统事故，也不是业务结果。业务码仍是 1000
     * （按码分支的调用方不受影响），HTTP 层给 400 让网关/监控把客户端错误与业务
     * 响应分开统计。此前维持 200 是"1~5xxx 老约定"，但该约定的本意是"业务失败
     * 仍算成功通信"，参数都读不进来不属此列。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e,
            jakarta.servlet.http.HttpServletResponse response) {
        StringBuilder sb = new StringBuilder();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            sb.append(fe.getField()).append(" ").append(fe.getDefaultMessage()).append("; ");
        }
        log.warn("param error: {}", sb);
        response.setStatus(400);
        return Result.fail(ErrorCode.PARAM_ERROR, sb.toString());
    }

    /**
     * 请求体不可读 / 参数类型不匹配（V6 2C 审查 m4）：JSON 解析失败、字符串填进
     * 数字/时间字段——此前落到兜底 handler 变成 200+5000 加一条 ERROR 假告警，
     * 与 L80 同病（调用方手误不是系统事故）。
     */
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public Result<Void> handleUnreadableRequest(Exception e,
            jakarta.servlet.http.HttpServletResponse response) {
        log.warn("request not readable: {}", e.getMessage());
        response.setStatus(400);
        return Result.fail(ErrorCode.PARAM_ERROR, "请求体或参数格式不合法（JSON 解析失败/类型不匹配）");
    }

    /** 兜底：预期外错误，ERROR 级别（触发告警），对用户只说"系统繁忙" */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("unexpected error", e);   // 完整堆栈进日志
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    private String detailOf(BizException e) {
        String full = e.getMessage();
        String base = e.getErrorCode().getMessage();
        return full.length() > base.length() ? full.substring(base.length() + 2) : "";
    }
}
