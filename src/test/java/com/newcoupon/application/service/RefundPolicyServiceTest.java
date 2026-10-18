package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/** 租户退款政策：租户显式配置 → 全站属性 → 默认；配错只回落，不抛异常。 */
class RefundPolicyServiceTest {

    @Test
    @DisplayName("租户三个键都覆盖全站")
    void tenantOverridesAllThree() {
        TenantConfigService config = mock(TenantConfigService.class);
        Environment env = mock(Environment.class);
        when(config.getRaw("app-A", "trade.refund.max_times")).thenReturn("2");
        when(config.getRaw("app-A", "trade.refund.max_return_times")).thenReturn("1");
        when(config.getRaw("app-A", "trade.refund.full_return_coupon")).thenReturn("false");

        RefundPolicy policy = new RefundPolicyService(config, env).resolve("app-A");

        assertThat(policy.maxRefundTimes()).isEqualTo(2);
        assertThat(policy.maxReturnTimes()).isEqualTo(1);
        assertThat(policy.fullRefundReturnCoupon()).isFalse();
    }

    @Test
    @DisplayName("租户没配 → 回落全站；全站没配 → 默认 50/3/true")
    void fallsBackToGlobalThenDefault() {
        TenantConfigService config = mock(TenantConfigService.class);
        Environment env = mock(Environment.class);
        when(config.getRaw("app-A", "trade.refund.max_times")).thenReturn(null);
        when(config.getRaw("app-A", "trade.refund.max_return_times")).thenReturn(null);
        when(config.getRaw("app-A", "trade.refund.full_return_coupon")).thenReturn(null);
        when(env.getProperty("coupon.trade.max-refund-times")).thenReturn("20");
        when(env.getProperty("coupon.trade.max-return-times")).thenReturn("5");
        when(env.getProperty("coupon.trade.full-refund-return-coupon")).thenReturn("false");

        RefundPolicy policy = new RefundPolicyService(config, env).resolve("app-A");

        assertThat(policy.maxRefundTimes()).isEqualTo(20);
        assertThat(policy.maxReturnTimes()).isEqualTo(5);
        assertThat(policy.fullRefundReturnCoupon()).isFalse();
    }

    @Test
    @DisplayName("租户配错 → 回落全站，不抛异常")
    void invalidTenantValueFallsBack() {
        TenantConfigService config = mock(TenantConfigService.class);
        Environment env = mock(Environment.class);
        when(config.getRaw("app-A", "trade.refund.max_times")).thenReturn("-1");
        when(config.getRaw("app-A", "trade.refund.max_return_times")).thenReturn("abc");
        when(config.getRaw("app-A", "trade.refund.full_return_coupon")).thenReturn("yes");

        RefundPolicy policy = new RefundPolicyService(config, env).resolve("app-A");

        assertThat(policy.maxRefundTimes()).isEqualTo(50);
        assertThat(policy.maxReturnTimes()).isEqualTo(3);
        assertThat(policy.fullRefundReturnCoupon()).isTrue();
    }

    @Test
    @DisplayName("全站布尔写错（flase）→ 回落默认 true，不能静默关掉退券")
    void invalidGlobalBooleanFallsBackToDefault() {
        TenantConfigService config = mock(TenantConfigService.class);
        Environment env = mock(Environment.class);
        when(env.getProperty("coupon.trade.full-refund-return-coupon")).thenReturn("flase");

        RefundPolicy policy = new RefundPolicyService(config, env).resolve(null);

        assertThat(policy.fullRefundReturnCoupon()).isTrue();
    }

    @Test
    @DisplayName("内部面/无 appId：只读全站")
    void noAppIdUsesGlobalOnly() {
        TenantConfigService config = mock(TenantConfigService.class);
        Environment env = mock(Environment.class);
        when(env.getProperty("coupon.trade.max-refund-times")).thenReturn("7");

        RefundPolicy policy = new RefundPolicyService(config, env).resolve(null);

        assertThat(policy.maxRefundTimes()).isEqualTo(7);
        assertThat(policy.maxReturnTimes()).isEqualTo(3);
        assertThat(policy.fullRefundReturnCoupon()).isTrue();
    }
}
