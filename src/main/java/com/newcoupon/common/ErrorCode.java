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
 *   7xxx   安全域（风控频控 7001 / 管理令牌 7002 / 风控决策拒绝 7003 / 管理面会话认证 7010-7012）
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
    /**
     * 路径不存在（V6 2C 清偿 L80）：无 handler 的路径/静态资源（Spring 6.1 的
     * {@code NoResourceFoundException}）配 HTTP 404。归 1xxx 通用段——它不属于任何
     * 业务域；域内"资源不存在"仍用各自专码（2001 模板/3005 任务/4008 退款单……），
     * 那些是"业务知道这个东西但找不到"，1002 是"连路由都没有"。
     */
    RESOURCE_NOT_FOUND(1002, "资源不存在"),

    // ---- 2xxx 模板域 ----
    TEMPLATE_NOT_FOUND(2001, "券模板不存在"),
    TEMPLATE_NOT_RECEIVABLE(2002, "券模板不可领取（未发布/不在领取期/审批未通过）"),
    TEMPLATE_STATE_INVALID(2003, "模板状态不允许该操作"),
    /**
     * 营销活动不存在或不属于当前应用（V6 阶段 1C）。
     * 两种原因统一一句文案：对开放面调用方区分"不存在"与"不归你"会暴露活动 id 的存在性
     * （与兑换码防枚举同一立场）。
     */
    ACTIVITY_NOT_FOUND(2004, "营销活动不存在或不属于当前应用"),

    // ---- 3xxx 领券域 ----
    STOCK_OUT(3001, "已售罄"),
    USER_LIMIT_EXCEEDED(3002, "已达限领上限"),
    DAILY_LIMIT_EXCEEDED(3003, "已达每日发放上限"),
    /** 券当前不可参与优惠计算（过期/未生效/状态不对/未达门槛）——业务不可用族，与参数错误(1000)区分 */
    COUPON_NOT_USABLE(3004, "优惠券当前不可用"),
    /** 异步发放任务查询：requestId 不存在或不属于当前租户/用户 */
    ISSUE_TASK_NOT_FOUND(3005, "发放任务不存在"),
    /** 批量发放任务查询/取消：taskNo 不存在或不属于当前应用（V6 阶段 1A） */
    GRANT_TASK_NOT_FOUND(3006, "批量发放任务不存在"),
    /**
     * 兑换码无效或已使用（V6 阶段 1B）。**统一文案防枚举**：码不存在/批停用/码停用/
     * 已兑换/兑换中/限流拦截全部用这一个码这一句文案——区分开哪一种失败，
     * 就等于告诉探测者"这个码是存在的"。真实区分只进 coupon_code_redeem_log。
     */
    CODE_INVALID(3007, "兑换失败：码无效或已使用"),
    /** 兑换码批次查询/导出：batchNo 不存在或不属于当前应用（V6 阶段 1B） */
    CODE_BATCH_NOT_FOUND(3008, "兑换码批次不存在"),
    /**
     * 预算已耗尽（V6 阶段 1C）：活动预算账户 reserved+consumed 已达 total，
     * 本次预占的条件更新打 0 行。只会在活动 budget_enabled=1 时出现——开关关着
     * 的存量活动永远不会返回这个码。
     */
    BUDGET_EXHAUSTED(3009, "活动预算已耗尽"),

    // ---- 4xxx 用券域 ----
    USER_COUPON_NOT_FOUND(4001, "用户券不存在"),
    STATE_CONFLICT(4002, "券状态冲突（并发流转或重复操作）"),
    USAGE_NOT_FOUND(4003, "占用单不存在"),
    USAGE_STATE_CONFLICT(4004, "占用单状态冲突"),
    COUPON_EXPIRED(4005, "券已过期（核销被拒，占用单已过期关闭）"),
    USAGE_TIMEOUT_CLOSED(4006, "占用单已超时关闭（券已回收/过期），核销拒绝——订单侧按无券价处理"),
    /**
     * 价格快照无效（环 2）：不存在/已过期/归属不符/券集合不一致/行不一致。
     * 一律 fail-closed，不静默回退到“按当前规则重算”——静默改价比拒绝更危险。
     */
    PRICING_SNAPSHOT_INVALID(4007, "价格快照无效（不存在/已过期/归属或券集合不一致）"),
    /** 退款查询（环 3）：按退款单号查明细但单号不存在 */
    REFUND_NOT_FOUND(4008, "退款单不存在"),

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
    CLIENT_TOKEN_MISSING(6012, "缺少 clientToken（写接口必须携带 X-Client-Token 头）"),
    /**
     * 租户生命周期不可调用（V6 2B）：SUSPENDED/CLOSING/CLOSED/DELETED 任一态的开放面调用
     * 在验签阶段被拒（HTTP 403）。这是<b>平台停用</b>语义，与"签名错"（6003）严格区分——
     * 接入方看到 6013 该去找平台运营，看到 6003 该查自己的签名实现。
     *
     * <p><b>统一文案防探测</b>：不区分具体是哪个状态（停用还是注销中），
     * 与 6002 的立场一致——区分开就等于向探测者播报租户的经营状态。
     * 设计文档附录 A.2 曾把 6013 规划为"沙箱环境禁止该操作"，本刀按实施裁定
     * 改用于 TENANT_NOT_ACTIVE（沙箱资金边界走 4002 STATE_CONFLICT，见 EnvironmentGuard）。
     */
    TENANT_NOT_ACTIVE(6013, "应用当前不可用（已停用或注销），请联系平台运营"),

    // ---- 7xxx 安全域（V6 2A：管理面会话认证族；HTTP 状态由安全链/控制器直接设置）----
    // 文案纪律：登录失败族（7011）不区分"用户不存在/密码错/锁定"——区分即用户名枚举。
    /**
     * 风控决策拒绝（V6 4A）：RiskDecisionService 判 REJECT 且场景处置=直接拒绝
     * （领券）。HTTP 403（GlobalExceptionHandler 映射）。**文案不区分命中了哪条规则**
     * ——"你被设备日限拦了"等于教攻击者换设备；具体命中看 risk_decision_log
     * （traceId 可查）。
     */
    RISK_REJECTED(7003, "请求被风控拦截，如有疑问请联系平台运营"),
    ADMIN_SESSION_REQUIRED(7010, "未登录或会话已过期"),
    ADMIN_LOGIN_FAILED(7011, "用户名或密码错误"),
    ADMIN_FORBIDDEN(7012, "无操作权限");

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
