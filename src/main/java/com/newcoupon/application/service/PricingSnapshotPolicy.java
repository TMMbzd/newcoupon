package com.newcoupon.application.service;

import com.newcoupon.common.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 价格快照灰度开关（环 2）。
 *
 * <p><b>为什么默认关</b>：带 {@code orderNo} 的试算会额外写一份快照，核销带
 * {@code pricingId} 时改走冻结价。先合代码、默认关，验证报价/核销/过期/跨租户后
 * 再按租户开启；关闭时 calculate 不返回 {@code pricingId}，lock/confirm 的旧行为不变。
 *
 * <p>取值：开放面（有 appId）读租户配置 {@code trade.pricing.enabled}，未配置回落
 * 全站属性 {@code coupon.trade.pricing.enabled}，再回落 false；内部面只读全站属性。
 * 配置读取失败不把试算打挂，记 WARN 后回落全站（与 SettlePartyPolicy/SwitchService 同策略）。
 */
@Service
public class PricingSnapshotPolicy {

    private static final Logger log = LoggerFactory.getLogger(PricingSnapshotPolicy.class);

    private final TenantConfigService tenantConfig;
    private final Environment environment;

    public PricingSnapshotPolicy(TenantConfigService tenantConfig, Environment environment) {
        this.tenantConfig = tenantConfig;
        this.environment = environment;
    }

    public boolean isEnabled() {
        String appId = RequestContext.current().appId();
        if (appId != null && !appId.isBlank()) {
            try {
                String raw = tenantConfig.getRaw(appId,
                        TenantConfigKey.TRADE_PRICING_ENABLED.configKey());
                if (raw != null
                        && TenantConfigKey.TRADE_PRICING_ENABLED.validate(raw) == null) {
                    return Boolean.parseBoolean(raw.trim());
                }
            } catch (RuntimeException e) {
                log.warn("读价格快照租户配置失败，本次回落全站：appId={} err={}", appId, e.toString());
            }
        }
        return Boolean.parseBoolean(
                environment.getProperty("coupon.trade.pricing.enabled", "false"));
    }
}
