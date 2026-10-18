package com.newcoupon.application.service;

import com.newcoupon.application.dto.TradeDtos.RefundResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 核销/退款最小业务指标（环 3）。
 *
 * <p><b>为什么只做这几条</b>：先让"动钱链路"的成功/失败/重放/金额在 Prometheus 上可见，
 * 不引入用户/订单标签（无界基数，见 `GovernanceMetrics` 的三条纪律）。
 * 大盘与告警面板属于后续运营，不在内核里画图。
 *
 * <p>标签只有 `result=success|duplicated|failed`，是固定枚举，基数可控。
 */
@Component
public class TradeMetrics {

    private final Counter confirmSuccess;
    private final Counter confirmDuplicated;
    private final Counter confirmFailed;
    private final Counter confirmAmount;
    private final Counter refundSuccess;
    private final Counter refundDuplicated;
    private final Counter refundFailed;
    private final Counter refundAmount;

    public TradeMetrics(MeterRegistry registry) {
        this.confirmSuccess = Counter.builder("coupon_confirm_total")
                .tag("result", "success").description("核销成功次数").register(registry);
        this.confirmDuplicated = Counter.builder("coupon_confirm_total")
                .tag("result", "duplicated").description("核销重放次数").register(registry);
        this.confirmFailed = Counter.builder("coupon_confirm_total")
                .tag("result", "failed").description("核销失败次数").register(registry);
        this.confirmAmount = Counter.builder("coupon_confirm_amount_total")
                .description("核销优惠总额（分）").register(registry);
        this.refundSuccess = Counter.builder("coupon_refund_total")
                .tag("result", "success").description("退款成功次数").register(registry);
        this.refundDuplicated = Counter.builder("coupon_refund_total")
                .tag("result", "duplicated").description("退款重放次数").register(registry);
        this.refundFailed = Counter.builder("coupon_refund_total")
                .tag("result", "failed").description("退款失败次数").register(registry);
        this.refundAmount = Counter.builder("coupon_refund_amount_total")
                .description("退款冲回优惠总额（分）").register(registry);
    }

    public void recordConfirm(boolean duplicated, long discountAmt) {
        if (duplicated) {
            confirmDuplicated.increment();
        } else {
            confirmSuccess.increment();
            confirmAmount.increment(discountAmt);
        }
    }

    public void recordConfirmFailed() {
        confirmFailed.increment();
    }

    public void recordRefund(RefundResult result) {
        if (result.duplicated()) {
            refundDuplicated.increment();
        } else {
            refundSuccess.increment();
            refundAmount.increment(result.discountAmt());
        }
    }

    public void recordRefundFailed() {
        refundFailed.increment();
    }
}
