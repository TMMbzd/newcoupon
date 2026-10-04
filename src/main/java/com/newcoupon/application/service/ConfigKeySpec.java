package com.newcoupon.application.service;

/**
 * "可配置键"的统一契约（S2 第 10 步·词表对齐）。
 *
 * <p><b>它解决什么</b>：租户配置的写入校验基于 {@link TenantConfigKey} 枚举的精确匹配，
 * 而开关清单是另一个枚举 {@link SwitchKey}。两套词表各自为政时，
 * {@code PUT switch.receive.enabled} 会被整体拒绝——"一键套用预案"第一次调用就失败，
 * 而且是在出事的时候才发现。
 *
 * <p><b>为什么不把 8 个开关复制进 TenantConfigKey</b>：两套清单必然漂移——
 * 漏复制一次，租户覆盖就静默失效；拼错一个键，语义永远读不到。
 * 而 {@code SwitchKey} 的立论本身就是"清单是给运维的说明书"，复制会毁掉这个性质。
 * 正确做法是两个枚举实现同一契约，校验入口做<b>组合解析</b>
 * （先查 {@code TenantConfigKey}，再查 {@code SwitchKey}）。
 *
 * <p><b>{@link #configKey()} 与 {@code key()} 为什么是两个方法</b>：后者是枚举自己的标识
 * （开关叫 {@code receive.enabled}），前者是<b>存储用的完整配置键</b>
 * （开关在 {@code open_app_config} 里叫 {@code switch.receive.enabled}）。
 * 两套命名各有历史，靠这一层转换对齐，而不是改其中一方的名字——
 * 改名字会波及 {@code coupon.switch.*} 全站配置键与既有文档。
 */
public interface ConfigKeySpec {

    /** 存储用的完整配置键（即 {@code open_app_config.config_key}） */
    String configKey();

    /** 人话描述——读取接口把它一并返回给运维，让"这个键管什么"不需要翻代码 */
    String description();

    /**
     * 校验取值是否可接受：返回 {@code null} 表示通过，否则返回**拒绝原因**。
     *
     * <p>写入严格、读取宽容——写入时严格让运维当场知道写错了；
     * 读取时宽容（回落默认值）让库里的历史坏数据不会把请求打挂。
     */
    String validate(String value);

    /** 读不到配置时用的值（描述"没配会怎样"）；{@code null} 表示"由调用方决定回落策略" */
    String defaultValue();
}
