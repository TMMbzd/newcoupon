package com.newcoupon.domain.open;

import java.time.LocalDateTime;

/**
 * 接入方（开放平台的"租户"主数据，S1）。
 *
 * <p>为什么它算领域概念而不是"配置"：它决定三件事——<b>谁能调</b>（status）、
 * <b>能调多少</b>（qpsLimit/dailyQuota）、<b>数据归谁</b>（appId 是券与模板的归属维度）。
 * 前两件是策略，第三件是<b>数据事实</b>——存量券的 app_id 一旦写入就不能随意改。
 *
 * <p>配额语义：{@code 0 = 不限}（内部应用用）。这比"给个很大的数"诚实——
 * 不限就是不做这个判断，不是"大到打不到"。
 *
 * @param appId      接入方标识（对外公开，签名头 Credential 里明文传）
 * @param appName    名称
 * @param status     0 停用 / 1 启用
 * @param qpsLimit   每秒请求上限（0=不限）
 * @param dailyQuota 每日请求配额（0=不限）
 * @param contact    联系人（运营用）
 */
public record OpenApp(String appId,
                      String appName,
                      int status,
                      int qpsLimit,
                      int dailyQuota,
                      String contact,
                      LocalDateTime createTime) {

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ENABLED = 1;

    /** appId 格式：小写字母/数字/连字符，3~32 字符，首字符必须是字母或数字 */
    public static final String APP_ID_PATTERN = "[a-z0-9][a-z0-9-]{2,31}";

    /** 内置应用：存量模板/券的归属方（V14 迁移写入），不走签名面 */
    public static final String BUILTIN_APP_ID = "1";

    public boolean enabled() {
        return status == STATUS_ENABLED;
    }
}
