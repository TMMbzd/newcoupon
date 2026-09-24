package com.newcoupon.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 领券链路最小指标（2e-⑤——"看得见改对了没有"，不做完整大盘）。
 *
 * <p>四件套：QPS（counter.rate）/ RT（timer 百分位）/ 结果分布（成功/各拒绝码）/
 * 库存急救命中（假售罄当场修触发次数——它默默救的每一次都该被看见）。
 * 每桶水位 gauge、MQ lag 归 P3 完整大盘批。
 */
@Component
public class ReceiveMetrics {

    private final Timer receiveTimer;
    private final Counter success;
    private final Counter soldOut;
    private final Counter frozen;
    private final Counter degraded;
    private final Counter userLimit;
    private final Counter falseSelloutAid;

    public ReceiveMetrics(MeterRegistry registry) {
        this.receiveTimer = Timer.builder("coupon_receive_duration")
                .description("领券端到端耗时").publishPercentiles(0.5, 0.99)
                .register(registry);
        this.success = Counter.builder("coupon_receive_total").tag("result", "success")
                .description("领券结果分布").register(registry);
        this.soldOut = Counter.builder("coupon_receive_total").tag("result", "sold_out").register(registry);
        this.frozen = Counter.builder("coupon_receive_total").tag("result", "frozen").register(registry);
        this.degraded = Counter.builder("coupon_receive_total").tag("result", "degraded").register(registry);
        this.userLimit = Counter.builder("coupon_receive_total").tag("result", "user_limit").register(registry);
        this.falseSelloutAid = Counter.builder("coupon_receive_false_sellout_aid").tag("kind", "rescued")
                .description("假售罄当场修命中（默默救的每一次都该被看见）").register(registry);
    }

    public Timer.Sample start() { return Timer.start(); }

    public void stop(Timer.Sample sample) { sample.stop(receiveTimer); }

    public void recordSuccess() { success.increment(); }
    public void recordSoldOut() { soldOut.increment(); }
    public void recordFrozen() { frozen.increment(); }
    public void recordDegraded() { degraded.increment(); }
    public void recordUserLimit() { userLimit.increment(); }
    public void recordFalseSelloutAid() { falseSelloutAid.increment(); }
}
