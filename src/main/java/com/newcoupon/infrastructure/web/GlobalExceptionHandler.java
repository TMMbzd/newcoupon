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

    /** 业务失败：预期内，WARN 级别（不触发告警响铃） */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("biz error: {}", e.getMessage());
        return Result.fail(e.getErrorCode(), detailOf(e));
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
