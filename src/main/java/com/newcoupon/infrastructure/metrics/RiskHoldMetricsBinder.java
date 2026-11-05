package com.newcoupon.infrastructure.metrics;

import com.newcoupon.infrastructure.persistence.mapper.CouponSettlementHoldMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 结算冻结指标的装配点（V6 4B）：把 coupon_risk_hold_amount gauge 注册到
 * {@link RiskMetrics}——数据源是 hold 表的 ACTIVE SUM（每次 scrape 现算）。
 *
 * <p>为什么单独一个类而不是塞进 SettlementHoldService：那个类的构造已经是纯依赖
 * 注入六件套，混入“注册指标”的副作用会让“服务装配”与“指标装配”搅在一起；
 * 也不塞进 RiskMetrics——它只认 MeterRegistry，不该知道任何 Mapper。这里就是
 * 两个世界的胶水，20 行以内、除注册外无行为。
 *
 * <p>依赖故障时返回 NaN 而不是抛异常：gauge 的求值发生在 Prometheus scrape 线程，
 * 抛出去会把整个 /actuator/prometheus 打 500——盘面缺一个点好过大盘全黑。
 */
@Component
public class RiskHoldMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(RiskHoldMetricsBinder.class);

    public RiskHoldMetricsBinder(RiskMetrics riskMetrics,
                                 CouponSettlementHoldMapper holdMapper) {
        riskMetrics.registerHoldAmountGauge(() -> {
            try {
                Long value = holdMapper.sumActiveAmount();
                return value == null ? 0d : value.doubleValue();
            } catch (RuntimeException e) {
                log.warn("coupon_risk_hold_amount 采样失败（本次返回 NaN，不阻断 scrape）", e);
                return Double.NaN;
            }
        });
    }
}
