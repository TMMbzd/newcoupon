package com.newcoupon.application.service;

import java.util.Map;

/**
 * 租户策略配置的读取服务（S2 第 1 步）。
 *
 * <p><b>它解决什么</b>：把散落在代码默认值与全局配置文件里的可调参数（限流算法与阈值、
 * 超时预算、熔断阈值、开关默认值）收敛成**按租户的一份策略**——把租户从一个"身份"
 * 变成一份"可运营的策略"，这是中台与普通系统的分界线。
 *
 * <p><b>三条不可让步的规则</b>：
 * <ol>
 *   <li><b>读不到就用默认值</b>：不让"配置缺失"变成"功能不可用"；</li>
 *   <li><b>解析失败也用默认值 + 记 WARN</b>：一个写错的配置项不该把请求打挂；</li>
 *   <li><b>调用方不必处理 null</b>：{@link #get} 对"无默认值"的键返回 null 是唯一的例外，
 *       那种键的语义是"由调用方决定回落"（如每秒上限回落登记表）。</li>
 * </ol>
 */
public interface TenantConfigService {

    /** 读一项配置；未配置或解析失败 → 返回该键的默认值（可能为 null，见类注释第 3 条） */
    String get(String appId, TenantConfigKey key);

    /**
     * 读整数配置。**解析失败回落默认值并记 WARN，绝不抛异常**——
     * 配置错误是运维问题，不该变成用户的请求失败。
     */
    int getInt(String appId, TenantConfigKey key);

    boolean getBool(String appId, TenantConfigKey key);

    /** 该租户的全部显式配置（管理面展示用） */
    Map<String, String> allOf(String appId);

    /**
     * 批量写入（管理面）。
     *
     * <p><b>先全部校验、再整体写入</b>：一批里有一个不合法就整体拒绝，不做部分写入——
     * 否则运维看到"报错了"，却不知道哪几项已经生效了。
     *
     * @param operator 操作者标识（管理面令牌 + 来源 IP），记进变更审计
     * @throws com.newcoupon.common.BizException 租户不存在 / 配置键未登记 / 值不合法
     */
    void put(String appId, Map<String, String> values, String operator);

    /** 删除一项配置（回落默认值），并记审计 */
    void remove(String appId, String key, String operator);

    /** 主动失效缓存（管理面改配置后调用） */
    void invalidate(String appId);
}
