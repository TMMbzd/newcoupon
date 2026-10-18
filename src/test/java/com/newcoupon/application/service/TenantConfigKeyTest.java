package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租户配置写入范围校验（2026-10-18 对抗审查发现：QPS/日配额/预热/熔断键只有类型校验，
 * 负值会被接受；例如 rate_limit.qps=-5 会在运行时时被当成“不限”，一次笔误静默关掉限流）。
 */
class TenantConfigKeyTest {

    @Test
    @DisplayName("QPS/日配额/预热：负值被拒绝，0 允许（0=不限/不预热）")
    void rateLimitKeysRejectNegative() {
        assertThat(TenantConfigKey.RATE_LIMIT_QPS.validate("-5")).isNotNull();
        assertThat(TenantConfigKey.RATE_LIMIT_QPS.validate("0")).isNull();
        assertThat(TenantConfigKey.RATE_LIMIT_DAILY_QUOTA.validate("-1")).isNotNull();
        assertThat(TenantConfigKey.RATE_LIMIT_DAILY_QUOTA.validate("0")).isNull();
        assertThat(TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS.validate("-1")).isNotNull();
        assertThat(TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS.validate("0")).isNull();
    }

    @Test
    @DisplayName("结算主体开关：只接受 true/false，默认 false")
    void settlePartySwitchIsBooleanAndOffByDefault() {
        assertThat(TenantConfigKey.TRADE_SETTLE_PARTY_ENABLED.validate("true")).isNull();
        assertThat(TenantConfigKey.TRADE_SETTLE_PARTY_ENABLED.validate("false")).isNull();
        assertThat(TenantConfigKey.TRADE_SETTLE_PARTY_ENABLED.validate("yes")).isNotNull();
        assertThat(TenantConfigKey.TRADE_SETTLE_PARTY_ENABLED.defaultValue()).isEqualTo("false");
    }

    @Test
    @DisplayName("价格快照开关：只接受 true/false，默认 false")
    void pricingSwitchIsBooleanAndOffByDefault() {
        assertThat(TenantConfigKey.TRADE_PRICING_ENABLED.validate("true")).isNull();
        assertThat(TenantConfigKey.TRADE_PRICING_ENABLED.validate("false")).isNull();
        assertThat(TenantConfigKey.TRADE_PRICING_ENABLED.validate("1")).isNotNull();
        assertThat(TenantConfigKey.TRADE_PRICING_ENABLED.defaultValue()).isEqualTo("false");
    }

    @Test
    @DisplayName("退款政策三键：范围/类型校验，默认 50/3/true")
    void refundPolicyKeysValidate() {
        assertThat(TenantConfigKey.TRADE_REFUND_MAX_TIMES.validate("0")).isNotNull();
        assertThat(TenantConfigKey.TRADE_REFUND_MAX_TIMES.validate("1001")).isNotNull();
        assertThat(TenantConfigKey.TRADE_REFUND_MAX_TIMES.validate("1")).isNull();
        assertThat(TenantConfigKey.TRADE_REFUND_MAX_TIMES.defaultValue()).isEqualTo("50");

        assertThat(TenantConfigKey.TRADE_REFUND_MAX_RETURN_TIMES.validate("-1")).isNotNull();
        assertThat(TenantConfigKey.TRADE_REFUND_MAX_RETURN_TIMES.validate("0")).isNull();
        assertThat(TenantConfigKey.TRADE_REFUND_MAX_RETURN_TIMES.validate("101")).isNotNull();

        assertThat(TenantConfigKey.TRADE_REFUND_FULL_RETURN_COUPON.validate("true")).isNull();
        assertThat(TenantConfigKey.TRADE_REFUND_FULL_RETURN_COUPON.validate("false")).isNull();
        assertThat(TenantConfigKey.TRADE_REFUND_FULL_RETURN_COUPON.validate("yes")).isNotNull();
        assertThat(TenantConfigKey.TRADE_REFUND_FULL_RETURN_COUPON.defaultValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("熔断三键：越界被拒绝，合法值通过")
    void circuitBreakerKeysRejectOutOfRange() {
        assertThat(TenantConfigKey.CIRCUIT_BREAKER_FAILURE_RATE.validate("0")).isNotNull();
        assertThat(TenantConfigKey.CIRCUIT_BREAKER_FAILURE_RATE.validate("101")).isNotNull();
        assertThat(TenantConfigKey.CIRCUIT_BREAKER_FAILURE_RATE.validate("50")).isNull();
        assertThat(TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS.validate("0")).isNotNull();
        assertThat(TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS.validate("60")).isNull();
    }
}
