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

    public SwitchService(Environment environment) {
        this.environment = environment;
    }

    /**
     * 开关是否开启。**未配置 = 开启**（默认全开的理由见 {@link SwitchKey}）。
     */
    public boolean isOn(SwitchKey key) {
        String raw = environment.getProperty(key.propertyName());
        return raw == null || Boolean.parseBoolean(raw.trim());
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
