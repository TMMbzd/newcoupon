package com.newcoupon.application.service;

/**
 * 开关清单（S1 治理插槽③）——**显式登记**而不是"到处 getProperty"。
 *
 * <p><b>为什么要做成枚举</b>：Spring 的 {@code Environment} <b>没法枚举"有哪些配置键"</b>
 * （它只能按键取值）。没有清单，开关就散落在代码各处，运维看不到"系统到底有哪些开关、
 * 各自什么状态"——出事时连"能关什么"都不知道。<b>清单本身就是给运维的说明书。</b>
 *
 * <p>每个开关带一句**人话描述**：读取接口把它一并返回，让"这个开关关掉会怎样"不需要翻代码。
 *
 * <p><b>命名规范</b>：{@code coupon.switch.<域>.<对象>.enabled}，与资源命名
 * （{@code RequestContext.resourceName()}）对齐——开关、限流、熔断、监控共用同一套 key 词汇。
 *
 * <p><b>默认全开</b>：开关是"出事时关"，不是"上线时开"。缺省为 true，
 * 少配一个开关不会让功能莫名消失。
 *
 * <p><b>S2 第 10 步起，开关可以按租户配置</b>：存储键是 {@code switch.<key>}
 * （见 {@link #configKey()}），落在 {@code open_app_config} 里，
 * 与其它租户策略共用一套写入校验、变更审计与缓存。取值三层：
 * 租户配置 → 全站配置（{@code coupon.switch.*}）→ 默认 true。
 */
public enum SwitchKey implements ConfigKeySpec {

    /** 领券总开关——关掉后所有领券请求返回 5001（可重试），库存与账目不受影响 */
    RECEIVE("receive.enabled", "领券入口总开关（关闭后领券返回 5001 可重试）"),

    /** 锁券开关——下单锁券入口 */
    TRADE_LOCK("trade.lock.enabled", "锁券入口开关（关闭后下单锁券返回 5001）"),

    /** 核销开关——支付成功核销入口 */
    TRADE_CONFIRM("trade.confirm.enabled", "核销入口开关（关闭后核销返回 5001）"),

    /** 释放开关——订单取消/超时释放入口 */
    TRADE_RELEASE("trade.release.enabled", "释放入口开关（关闭后释放返回 5001）"),

    /** 建券发布开关——关掉后不能再发布新券（已发布的券照常发放） */
    TEMPLATE_PUBLISH("template.publish.enabled", "券模板发布开关（关闭后不可发布新券）"),

    /** Redis 依赖开关——S2 的调用熔断会接管这里；S1 先留位并可见 */
    REDIS_DEPENDENCY("redis.dependency.enabled", "Redis 依赖开关（关闭后依赖 Redis 的路径返回 5001）"),

    /** 发件箱投递开关——关掉后投递器跳过本轮（消息不丢，恢复后补投） */
    OUTBOX_DISPATCH("outbox.dispatch.enabled", "发件箱投递开关（关闭后暂停投递，消息不丢）"),

    /** 触达外呼开关——S6 的触达通道用；S1 先登记，保证清单完整 */
    MESSAGE_CHANNEL("message.channel.enabled", "触达外呼开关（关闭后不发送消息，仅落表）");

    private final String key;
    private final String description;

    SwitchKey(String key, String description) {
        this.key = key;
        this.description = description;
    }

    /** 配置键（不带前缀） */
    public String key() {
        return key;
    }

    /** 完整的配置属性名，如 {@code coupon.switch.receive.enabled} */
    public String propertyName() {
        return "coupon.switch." + key;
    }

    public String description() {
        return description;
    }

    // ------------------------------------------------------------------
    // ConfigKeySpec（S2 第 10 步·词表对齐）
    // ------------------------------------------------------------------

    /**
     * 在 {@code open_app_config} 里的存储键，如 {@code switch.receive.enabled}。
     *
     * <p>与 {@link #key()} 的区别：后者是开关自己的标识（{@code receive.enabled}），
     * 前者带 {@code switch.} 前缀——配置表里一眼能看出"这一项是开关"。
     */
    @Override
    public String configKey() {
        return "switch." + key;
    }

    /** 开关只接受 true/false——拼错的布尔值必须在写入时就被挡住 */
    @Override
    public String validate(String value) {
        if (value == null) {
            return "配置值不能为 null（要删除请用删除接口）";
        }
        String trimmed = value.trim();
        if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
            return "该配置项需要 true/false，收到：" + value;
        }
        return null;
    }

    /** 没配过时的行为：开（见类注释"默认全开"） */
    @Override
    public String defaultValue() {
        return "true";
    }

    /**
     * 按存储键反查（{@code switch.receive.enabled} → {@code RECEIVE}）；未登记返回 {@code null}。
     *
     * <p><b>只做精确匹配，不做前缀匹配</b>：写成"以 switch. 开头就放行"会让任意垃圾键
     * 写进配置表。有一条用例专门钉这个边界。
     */
    public static SwitchKey fromConfigKey(String configKey) {
        if (configKey == null) {
            return null;
        }
        for (SwitchKey k : values()) {
            if (k.configKey().equals(configKey)) {
                return k;
            }
        }
        return null;
    }
}
