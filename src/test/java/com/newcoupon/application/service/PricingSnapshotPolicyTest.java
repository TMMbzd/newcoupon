package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcoupon.common.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/** 价格快照灰度取值：租户显式配置 → 全站属性 → 默认 false。 */
class PricingSnapshotPolicyTest {

    private static final String KEY = "trade.pricing.enabled";

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("租户显式 false 覆盖全站 true")
    void tenantFalseOverridesGlobalTrue() {
        TenantConfigService config = mock(TenantConfigService.class);
        Environment env = mock(Environment.class);
        when(config.getRaw("app-A", KEY)).thenReturn("false");
        when(env.getProperty("coupon.trade.pricing.enabled", "false")).thenReturn("true");
        RequestContext.set(new RequestContext("app-A", "t", Long.MAX_VALUE, false));

        assertThat(new PricingSnapshotPolicy(config, env).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("租户没配 → 回落全站；全站没有 → 默认 false")
    void fallsBackToGlobalThenFalse() {
        TenantConfigService config = mock(TenantConfigService.class);
        Environment env = mock(Environment.class);
        when(config.getRaw("app-A", KEY)).thenReturn(null);
        when(env.getProperty("coupon.trade.pricing.enabled", "false")).thenReturn("true");
        RequestContext.set(new RequestContext("app-A", "t", Long.MAX_VALUE, false));
        assertThat(new PricingSnapshotPolicy(config, env).isEnabled()).isTrue();

        when(env.getProperty("coupon.trade.pricing.enabled", "false")).thenReturn("false");
        assertThat(new PricingSnapshotPolicy(config, env).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("内部面无 appId：只读全站属性，默认 false")
    void internalFaceUsesGlobalOnly() {
        TenantConfigService config = mock(TenantConfigService.class);
        Environment env = mock(Environment.class);
        when(env.getProperty("coupon.trade.pricing.enabled", "false")).thenReturn("false");

        assertThat(new PricingSnapshotPolicy(config, env).isEnabled()).isFalse();
    }
}
