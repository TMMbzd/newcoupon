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
 *   6xxx   接入域（S1 新增：签名/配额/幂等/版本/越权——"还没进业务就被拦下"）
 *   7xxx   安全域（风控频控 7001 / 管理令牌 7002）
 * </pre>
 *
 * <p><b>为什么 6xxx 要单独一段</b>：它表示"请求连业务逻辑都没跑到就被接入层拒了"——
 * 排障时看到 6xxx 就不该去翻业务日志，看监控时它能和"业务内失败"分开统计。
 * 把接入失败混进 1xxx，这两种截然不同的现场就会被搅在一起。
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
    /** 券当前不可参与优惠计算（过期/未生效/状态不对/未达门槛）——业务不可用族，与参数错误(1000)区分 */
    COUPON_NOT_USABLE(3004, "优惠券当前不可用"),

    // ---- 4xxx 用券域 ----
    USER_COUPON_NOT_FOUND(4001, "用户券不存在"),
    STATE_CONFLICT(4002, "券状态冲突（并发流转或重复操作）"),
    USAGE_NOT_FOUND(4003, "占用单不存在"),
    USAGE_STATE_CONFLICT(4004, "占用单状态冲突"),
    COUPON_EXPIRED(4005, "券已过期（核销被拒，占用单已过期关闭）"),
    USAGE_TIMEOUT_CLOSED(4006, "占用单已超时关闭（券已回收/过期），核销拒绝——订单侧按无券价处理"),

    // ---- 5xxx 系统与依赖 ----
    SYSTEM_ERROR(5000, "系统繁忙，请稍后重试"),
    DEGRADED(5001, "系统降级中，请稍后重试"),
    /** P2b v2 §7（USAB-2）：冻结专用降级码——可重试语义，响应体禁止泄漏内部负数码（-11） */
    FROZEN(5002, "活动暂停发放，请稍后重试"),

    /**
     * S2 第 9 步：请求的时间预算（X-Timeout-Ms）已耗尽——可重试。
     *
     * <p><b>为什么要单独一个码而不是复用 5001</b>：5001 同时被"熔断降级"和"Redis 故障"用着，
     * 再加一个"预算耗尽"就三个来源同一个码，排障时只能靠猜。
     *
     * <p><b>为什么必须落在 5xxx 段</b>：幂等过滤器对 {@code code ∈ [5000,6000)} 不落快照——
     * 落在别处会让一次瞬时抖动被缓存成 24 小时的确定性失败。
     */
    REQUEST_BUDGET_EXHAUSTED(5003, "请求时间预算已耗尽，请稍后重试"),

    // ---- 6xxx 接入域（S1）----
    // HTTP 状态映射见 docs/S1-开放接入层-需求与设计-2026-10-01.md §4.10：
    //   6001~6005 → 401；6006/6007 → 429；6008/6009 → 409；6010 → 400；6011 → 403
    SIGN_MISSING(6001, "签名头缺失或格式错误"),
    APP_INVALID(6002, "应用不存在或已停用"),
    SIGN_MISMATCH(6003, "签名不匹配"),
    TIMESTAMP_OUT_OF_WINDOW(6004, "时间戳超出允许窗口"),
    NONCE_REPLAYED(6005, "请求已被处理过（nonce 重放）"),
    QPS_LIMIT_EXCEEDED(6006, "请求过于频繁，请稍后重试"),
    DAILY_QUOTA_EXCEEDED(6007, "今日调用配额已用尽"),
    CLIENT_TOKEN_CONFLICT(6008, "clientToken 重复但请求参数不一致"),
    CLIENT_TOKEN_PROCESSING(6009, "相同 clientToken 的请求正在处理中，请稍后重试"),
    API_VERSION_UNSUPPORTED(6010, "不支持的接口版本"),
    RESOURCE_FORBIDDEN(6011, "无权访问该资源"),
    CLIENT_TOKEN_MISSING(6012, "缺少 clientToken（写接口必须携带 X-Client-Token 头）");

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
