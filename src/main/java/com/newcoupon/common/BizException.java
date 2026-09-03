package com.newcoupon.common;

/**
 * 业务异常：业务规则不满足时抛出（如已售罄、状态冲突），由全局异常处理器统一转成 Result。
 *
 * <p>规范：
 * <ul>
 *   <li>业务失败用 BizException（预期内的失败，日志级别 WARN）；</li>
 *   <li>真正的程序错误（NPE/IO）直接抛原生异常（预期外的失败，日志级别 ERROR）——
 *       两者的告警渠道和响应级别完全不同，混在一起会让告警噪音淹没真事故。</li>
 * </ul>
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + ": " + detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
