package com.newcoupon.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 租户级退款政策（环 3）：最多退款次数、退券次数、整单退是否退券。
 *
 * <p><b>取值顺序</b>：租户显式配置（`open_app_config`）→ 全站属性
 * （`coupon.trade.max-refund-times` / `max-return-times` / `full-refund-return-coupon`）
 * → 代码默认（50 / 3 / true）。租户配置用 {@link TenantConfigService#getRaw}
 * 读取，只有"配过且合法"才覆盖；没配过/配错都回落全站。
 *
 * <p><b>按哪个 appId 解析</b>：调用方传券的 `app_id`，不是请求上下文。
 * 原因：内部面（平台管理员）没有 appId，但券属于某个租户，政策必须跟着券走；
 * 开放面调用方与券归属已由归属校验保证一致。
 *
 * <p><b>为什么配置错误不抛异常</b>：配置是运维问题，不能让用户退款失败；
 * 记 WARN 后回落全站，与 `TenantConfigService` 的“读取宽容”原则一致。
 */
@Service
public class RefundPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RefundPolicyService.class);

    private static final int DEFAULT_MAX_REFUND_TIMES = 50;
    private static final int DEFAULT_MAX_RETURN_TIMES = 3;
    private static final boolean DEFAULT_FULL_RETURN_COUPON = true;

    private final TenantConfigService tenantConfig;
    private final Environment environment;

    public RefundPolicyService(TenantConfigService tenantConfig, Environment environment) {
        this.tenantConfig = tenantConfig;
        this.environment = environment;
    }

    public RefundPolicy resolve(String appId) {
        int maxRefund = intPolicy(appId, TenantConfigKey.TRADE_REFUND_MAX_TIMES,
                "coupon.trade.max-refund-times", DEFAULT_MAX_REFUND_TIMES, 1, 1000);
        int maxReturn = intPolicy(appId, TenantConfigKey.TRADE_REFUND_MAX_RETURN_TIMES,
                "coupon.trade.max-return-times", DEFAULT_MAX_RETURN_TIMES, 0, 100);
        boolean fullReturn = boolPolicy(appId, TenantConfigKey.TRADE_REFUND_FULL_RETURN_COUPON,
                "coupon.trade.full-refund-return-coupon", DEFAULT_FULL_RETURN_COUPON);
        return new RefundPolicy(maxRefund, maxReturn, fullReturn);
    }

    private int intPolicy(String appId, TenantConfigKey key, String globalProperty,
                          int fallback, int min, int max) {
        if (appId != null && !appId.isBlank()) {
            try {
                String raw = tenantConfig.getRaw(appId, key.configKey());
                if (raw != null) {
                    if (key.validate(raw) == null) {
                        return Integer.parseInt(raw.trim());
                    }
                    log.warn("REFUND_POLICY_INVALID appId={} key={} value={}，回落全站",
                            appId, key.configKey(), raw);
                }
            } catch (RuntimeException e) {
                log.warn("REFUND_POLICY_READ_FAIL appId={} key={} err={}，回落全站",
                        appId, key.configKey(), e.toString());
            }
        }
        int parsed = fallback;
        String raw = environment.getProperty(globalProperty);
        if (raw != null) {
            try {
                parsed = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                log.warn("REFUND_POLICY_GLOBAL_INVALID property={} value={}，用默认 {}",
                        globalProperty, raw, fallback);
            }
        }
        return Math.min(max, Math.max(min, parsed));
    }

    private boolean boolPolicy(String appId, TenantConfigKey key, String globalProperty,
                               boolean fallback) {
        if (appId != null && !appId.isBlank()) {
            try {
                String raw = tenantConfig.getRaw(appId, key.configKey());
                if (raw != null) {
                    if (key.validate(raw) == null) {
                        return Boolean.parseBoolean(raw.trim());
                    }
                    log.warn("REFUND_POLICY_INVALID appId={} key={} value={}，回落全站",
                            appId, key.configKey(), raw);
                }
            } catch (RuntimeException e) {
                log.warn("REFUND_POLICY_READ_FAIL appId={} key={} err={}，回落全站",
                        appId, key.configKey(), e.toString());
            }
        }
        String raw = environment.getProperty(globalProperty);
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
            log.warn("REFUND_POLICY_GLOBAL_INVALID property={} value={}，用默认 {}",
                    globalProperty, raw, fallback);
            return fallback;
        }
        return Boolean.parseBoolean(trimmed);
    }
}
