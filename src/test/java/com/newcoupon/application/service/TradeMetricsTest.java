package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.TradeDtos.RefundResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 核销/退款最小指标：只验证计数语义，不依赖 Spring/容器。 */
class TradeMetricsTest {

    @Test
    @DisplayName("核销成功/重放/失败分别计数；成功累计优惠金额")
    void confirmCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TradeMetrics metrics = new TradeMetrics(registry);

        metrics.recordConfirm(false, 1000);
        metrics.recordConfirm(true, 0);
        metrics.recordConfirmFailed();

        assertThat(registry.get("coupon_confirm_total").tag("result", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("coupon_confirm_total").tag("result", "duplicated")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("coupon_confirm_total").tag("result", "failed")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("coupon_confirm_amount_total").counter().count())
                .isEqualTo(1000);
    }

    @Test
    @DisplayName("标签有界：不把 appId/主体/单号/模板放进标签")
    void tagsAreBounded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new TradeMetrics(registry);

        java.util.Set<String> unbounded = java.util.Set.of(
                "appId", "settlePartyId", "refundNo", "usageNo", "orderNo", "templateId");
        assertThat(registry.getMeters()).allSatisfy(m ->
                assertThat(m.getId().getTags()).noneMatch(t -> unbounded.contains(t.getKey())));
    }

    @Test
    @DisplayName("退款成功累计金额；重放不重复计金额")
    void refundCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TradeMetrics metrics = new TradeMetrics(registry);

        metrics.recordRefund(new RefundResult("U1", "R1", "PARTIAL", "已核销",
                "部分退款", 600, false));
        metrics.recordRefund(new RefundResult("U1", "R1", "PARTIAL", "已核销",
                "部分退款", 600, true));
        metrics.recordRefundFailed();

        assertThat(registry.get("coupon_refund_total").tag("result", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("coupon_refund_total").tag("result", "duplicated")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("coupon_refund_total").tag("result", "failed")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("coupon_refund_amount_total").counter().count())
                .as("重放不重复计金额").isEqualTo(600);
    }
}
