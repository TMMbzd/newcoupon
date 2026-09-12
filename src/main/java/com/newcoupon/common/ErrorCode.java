package com.newcoupon.common;

/**
 * 全局错误码（分段规范，对标阿里巴巴《Java开发手册》错误码规约的简化版）。
 *
 * <p>为什么要错误码而不是裸字符串？——上游（未来的订单服务、多业务方）需要**程序化地**区分
 * "已售罄(不可重试)"和"系统繁忙(可重试)"。字符串只能给人看，错误码才能给程序做分支。
 *
 * <p>分段规则（新增错误码必须归入对应段，禁止随手跳号）：
 * <pre>
 *   0      成功
 *   1xxx   参数与通用业务错误
 *   2xxx   模板域（创）
 *   3xxx   领券域（发）
 *   4xxx   用券域（用：锁券/核销/回退）
 *   5xxx   系统与依赖（计/控）
 * </pre>
 */
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // ---- 1xxx 参数与通用 ----
    PARAM_ERROR(1000, "参数错误"),
    REQUEST_DUPLICATED(1001, "重复请求（幂等命中，按成功处理）"),

    // ---- 2xxx 模板域 ----
    TEMPLATE_NOT_FOUND(2001, "券模板不存在"),
    TEMPLATE_NOT_RECEIVABLE(2002, "券模板不可领取（未发布/不在领取期/审批未通过）"),
    TEMPLATE_STATE_INVALID(2003, "模板状态不允许该操作"),

    // ---- 3xxx 领券域 ----
    STOCK_OUT(3001, "已售罄"),
    USER_LIMIT_EXCEEDED(3002, "已达限领上限"),
    DAILY_LIMIT_EXCEEDED(3003, "已达每日发放上限"),

    // ---- 4xxx 用券域 ----
    USER_COUPON_NOT_FOUND(4001, "用户券不存在"),
    STATE_CONFLICT(4002, "券状态冲突（并发流转或重复操作）"),
    USAGE_NOT_FOUND(4003, "占用单不存在"),
    USAGE_STATE_CONFLICT(4004, "占用单状态冲突"),
    COUPON_EXPIRED(4005, "券已过期（核销被拒，占用单已过期关闭）"),

    // ---- 5xxx 系统与依赖 ----
    SYSTEM_ERROR(5000, "系统繁忙，请稍后重试"),
    DEGRADED(5001, "系统降级中，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
