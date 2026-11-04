package com.newcoupon.domain.open;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 接入方（开放平台的"租户"主数据，S1）。
 *
 * <p>为什么它算领域概念而不是"配置"：它决定三件事——<b>谁能调</b>（status +
 * {@link #lifecycle lifecycle}）、<b>能调多少</b>（qpsLimit/dailyQuota）、
 * <b>数据归谁</b>（appId 是券与模板的归属维度）。前两件是策略，第三件是<b>数据事实</b>
 * ——存量券的 app_id 一旦写入就不能随意改。
 *
 * <p><b>三条正交的轴（V6 2B 起）</b>：
 * <ul>
 *   <li>{@code status}——S1 运营开关（0 停用 / 1 启用），语义"临时拉闸"，可随时切回；</li>
 *   <li>{@code lifecycle}——生命周期（见 {@link AppLifecycle}），语义"租户走到了哪一步"，
 *       只能沿状态机走，SUSPENDED 与 status 停用是两件事（前者是注销前置的正式停用）；</li>
 *   <li>{@code environment}——环境（PROD/SANDBOX），语义"这是什么环境"，
 *       决定资金边界（沙箱不进账单/退款/预算，见 EnvironmentGuard）。</li>
 * </ul>
 *
 * <p>配额语义：{@code 0 = 不限}（内部应用用）。这比"给个很大的数"诚实——
 * 不限就是不做这个判断，不是"大到打不到"。
 *
 * @param appId          接入方标识（对外公开，签名头 Credential 里明文传）
 * @param appName        名称
 * @param status         0 停用 / 1 启用（S1 运营开关）
 * @param qpsLimit       每秒请求上限（0=不限）
 * @param dailyQuota     每日请求配额（0=不限）
 * @param contact        联系人（运营用）
 * @param createTime     创建时刻
 * @param environment    PROD / SANDBOX（V45；沙箱在资金边界被拒）
 * @param lifecycle      生命周期状态（V45；见 {@link AppLifecycle}）
 * @param closedAt       注销完成时刻（confirm_close 写入，其余为 null）
 * @param retentionUntil 数据保留截止（confirm_close 按默认保留期写入；清理是手动事项）
 * @param legalHold      法务保留（1=禁止删除）
 */
public record OpenApp(String appId,
                      String appName,
                      int status,
                      int qpsLimit,
                      int dailyQuota,
                      String contact,
                      LocalDateTime createTime,
                      String environment,
                      AppLifecycle lifecycle,
                      LocalDateTime closedAt,
                      LocalDate retentionUntil,
                      int legalHold) {

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ENABLED = 1;

    /** 环境：生产（资金链路正常） */
    public static final String ENV_PROD = "PROD";
    /** 环境：沙箱（联调用；建券/发放照常，资金边界三处被拒） */
    public static final String ENV_SANDBOX = "SANDBOX";

    /** appId 格式：小写字母/数字/连字符，3~32 字符，首字符必须是字母或数字 */
    public static final String APP_ID_PATTERN = "[a-z0-9][a-z0-9-]{2,31}";

    /** 沙箱 appId 约定前缀：带它即沙箱（与 environment 参数二者任一即成立） */
    public static final String SANDBOX_PREFIX = "sbx-";

    /** 内置应用：存量模板/券的归属方（V14 迁移写入），不走签名面 */
    public static final String BUILTIN_APP_ID = "1";

    public boolean enabled() {
        return status == STATUS_ENABLED;
    }

    public boolean sandbox() {
        return ENV_SANDBOX.equals(environment);
    }
}
