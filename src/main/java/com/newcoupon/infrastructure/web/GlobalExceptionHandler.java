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
        // （迁移第 5 步：日配额改由 Sentinel 的 slot 抛出 BizException 之后，
        //   这个头原来是在过滤器里手写的，现在统一在这里补，两条路径都不会漏。）
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
            case 6010, 6012 -> 400;          // 版本 / 缺 clientToken
            default -> 401;                  // 6001~6005：签名与身份
        };
    }

    /** 参数校验失败：JSR-303 注解校验不通过时，把所有字段错误拼出来一次性返回 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        StringBuilder sb = new StringBuilder();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            sb.append(fe.getField()).append(" ").append(fe.getDefaultMessage()).append("; ");
        }
        log.warn("param error: {}", sb);
        return Result.fail(ErrorCode.PARAM_ERROR, sb.toString());
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
