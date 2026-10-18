package com.newcoupon.application.service;

import com.newcoupon.common.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 结算主体灰度开关（环 1）。
 *
 * <p><b>为什么需要开关</b>：主体字段会改变“券命中哪些行/退款落哪个主体”的行为。
 * 先合代码、默认关，验证 A/B 主体、退款、旧调用方后再按租户开启；关闭时行为与旧版一致
 * （忽略主体字段，所有行落 {@code __default__}）。
 *
 * <p><b>取值</b>：
 * <ul>
 *   <li>开放面（有 appId）：读租户配置 {@code trade.settle_party.enabled}，默认 false；</li>
 *   <li>内部面/定时任务（无 appId）：读全站属性 {@code coupon.trade.settle-party.enabled}，默认 false。</li>
 * </ul>
 */
@Service
public class SettlePartyPolicy {

    private static final Logger log = LoggerFactory.getLogger(SettlePartyPolicy.class);

    private final TenantConfigService tenantConfig;
    private final Environment environment;

    public SettlePartyPolicy(TenantConfigService tenantConfig, Environment environment) {
        this.tenantConfig = tenantConfig;
        this.environment = environment;
    }

    /** 当前请求是否启用结算主体逻辑（租户显式配置 → 全站属性 → 默认 false） */
    public boolean isEnabled() {
        String appId = RequestContext.current().appId();
        if (appId != null && !appId.isBlank()) {
            try {
                String raw = tenantConfig.getRaw(appId,
                        TenantConfigKey.TRADE_SETTLE_PARTY_ENABLED.configKey());
                if (raw != null
                        && TenantConfigKey.TRADE_SETTLE_PARTY_ENABLED.validate(raw) == null) {
                    return Boolean.parseBoolean(raw.trim());
                }
            } catch (RuntimeException e) {
                // 配置读取失败不能把交易打挂：退回全站属性（与 SwitchService 同策略）
                log.warn("读结算主体租户配置失败，本次回落全站：appId={} err={}", appId, e.toString());
            }
        }
        return Boolean.parseBoolean(
                environment.getProperty("coupon.trade.settle-party.enabled", "false"));
    }
}
