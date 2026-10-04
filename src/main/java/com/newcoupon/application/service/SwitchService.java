package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 开关中心（S1 第 9 步，治理插槽③）。
 *
 * <p><b>S1 的形态是"配置读取 + 真实生效"</b>：值来自配置文件/环境变量，读一次判断一次，
 * 改配置重启即生效。S2 会把它升级为**开关中心 + 三级预案**（热更新、按活动/按接入方细分、
 * 与预案联动）——那时本类的接口不变，只换实现（接配置中心或 Redis），**调用点零改动**。
 *
 * <p><b>为什么区分"三类开关/熔断"</b>（本项目反复强调的一条边界）：
 * <ul>
 *   <li><b>业务开关</b>（本类）：产品层——"这个功能现在要不要对外提供"；</li>
 *   <li><b>调用熔断</b>（S2，Resilience4j）：技术层——"依赖挂了，别再打它了"；</li>
 *   <li><b>修复环熔断</b>（P2 已有）：运维层——"自动修复连续失败，停手止损"。</li>
 * </ul>
 * 三者常被混为一谈，但责任人与触发条件完全不同。
 *
 * <p><b>关闭后的统一语义是"可重试"（5001 DEGRADED）</b>，而不是"不可用"：
 * 开关多半是临时的，告诉调用方"稍后重试"比"这个功能没了"更贴近事实。
 */
@Service
public class SwitchService {

    private static final Logger log = LoggerFactory.getLogger(SwitchService.class);

    private final Environment environment;
    private final TenantConfigService tenantConfig;

    public SwitchService(Environment environment, TenantConfigService tenantConfig) {
        this.environment = environment;
        this.tenantConfig = tenantConfig;
    }

    /**
     * 开关是否开启（不带租户上下文）。**未配置 = 开启**（默认全开的理由见 {@link SwitchKey}）。
     *
     * <p>内部面与定时任务走这里（它们没有接入方身份）；开放面走
     * {@link #isOn(SwitchKey, String)} 以启用租户层覆盖。
     */
    public boolean isOn(SwitchKey key) {
        return isOn(key, null);
    }

    /**
     * 开关是否开启——**三层取值**（S2 第 10 步）。
     *
     * <pre>
     * ① 租户层（open_app_config: switch.receive.enabled）  ← 最具体，有则用
     * ② 全站层（coupon.switch.receive.enabled）
     * ③ 默认 true
     * </pre>
     *
     * <p><b>为什么"没配过"必须与"配了 false"分开</b>：三层取值里，租户层"没配过"的语义是
     * <b>继承全站层</b>，而不是"取自己的默认值"。所以判断租户层用的是
     * {@link TenantConfigService#getRaw}（可空），**不能用 {@code getBool}**——
     * 后者对 null 返回 false，会把"没配过"误判成"显式关闭"，
     * 结果是一个什么都没配的租户，所有开关被静默关掉。
     *
     * @param appId 接入方身份（来自 {@code RequestContext}）。
     *              {@code null} = 无租户上下文（内部面、定时任务）→ 跳过租户层。
     *              <b>这条不对称是有意的</b>：内部调用没有"是谁在调"的身份，
     *              租户策略对它无从谈起。
     */
    public boolean isOn(SwitchKey key, String appId) {
        // ① 租户层
        String tenantRaw = readTenantRawQuietly(appId, key);
        if (tenantRaw != null) {
            if (key.validate(tenantRaw) == null) {
                return Boolean.parseBoolean(tenantRaw.trim());
            }
            // 值非法（只可能来自手工改库——写入时已被校验挡住）：**忽略租户层，继续往下走**。
            // 注意不能"非法就返回 true"：那会让这个租户借一个拼错的值绕过全站关闭。
            log.warn("租户开关取值非法，忽略该层并回落全站配置：appId={} key={} value={}",
                    appId, key.key(), tenantRaw);
        }
        // ② 全站层
        String globalRaw = environment.getProperty(key.propertyName());
        if (globalRaw != null) {
            return Boolean.parseBoolean(globalRaw.trim());
        }
        // ③ 默认全开
        return true;
    }

    /**
     * 读租户层的原始值；无租户上下文或读取失败 → {@code null}（等价于"该层没有发言权"）。
     *
     * <p><b>为什么这里还要 catch 一次</b>：{@code CachedTenantConfigService} 内部已经对回源失败
     * 做了兜底，但开关判断挂在<b>全路径拦截器</b>上——任何一次意外异常都会让所有接口一起失败。
     * 保命组件对"自己出问题"必须比对"依赖出问题"更保守：**宁可退化成只用全站层**。
     */
    private String readTenantRawQuietly(String appId, SwitchKey key) {
        if (appId == null || appId.isBlank()) {
            return null;
        }
        try {
            return tenantConfig.getRaw(appId, key.configKey());
        } catch (RuntimeException e) {
            log.warn("读租户开关配置失败，本次跳过租户层：appId={} key={} err={}",
                    appId, key.key(), e.toString());
            return null;
        }
    }

    /**
     * 断言开关开启，否则抛 {@link ErrorCode#DEGRADED}（5001 可重试）。
     *
     * <p>用抛异常而不是返回 boolean 让调用方自己 if：**开关判断的位置必须显眼**。
     * 返回 boolean 的写法迟早会被写成 {@code if (!on) {}} 或者干脆忘了判。
     */
    public void requireOn(SwitchKey key) {
        if (!isOn(key)) {
            log.warn("SWITCH OFF: {} ({}) —— 按降级处理", key.key(), key.description());
            throw new BizException(ErrorCode.DEGRADED, "功能已关闭：" + key.description());
        }
    }

    /** 全部开关及其当前状态（管理面读取接口用）——运维的"我能关什么"清单 */
    public List<SwitchView> all() {
        return Arrays.stream(SwitchKey.values()).map(key -> {
            String raw = environment.getProperty(key.propertyName());
            return new SwitchView(key.key(), key.description(),
                    raw == null || Boolean.parseBoolean(raw.trim()),
                    raw != null);   // configured=false 表示"取的是默认值"，不是有人显式配过
        }).toList();
    }

    /**
     * @param key         开关键
     * @param description 人话描述
     * @param on          当前是否开启
     * @param configured  是否被显式配置过（false = 正在用默认值 true）
     */
    public record SwitchView(String key, String description, boolean on, boolean configured) {
    }
}
