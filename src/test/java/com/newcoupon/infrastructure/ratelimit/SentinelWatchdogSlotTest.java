package com.newcoupon.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 处理链看门狗（迁移第 7 步，2026-10-08）。
 *
 * <p><b>它在补哪个洞</b>：{@code CtSph.entryWithPriority} 对链内部异常的处理是
 * "记一行 Sentinel 自己的日志，然后**照常返回 Entry**"——也就是**静默放行**。
 * 调用方（我们的过滤器）拿到的是一个正常 Entry，**catch 不到任何东西**；
 * 于是"这一发的限流判定其实没生效"这件事，在应用日志与指标里全都是空白。
 *
 * <p>（这条结论来自第 7 步的源码级核实与探针实测：下游 Slot 抛错时，MetricExtension 会把它
 * 装扮成"业务异常 + 成功"，而上游 Slot 抛错时连一个事件都没有。）
 *
 * <p><b>本类钉住两件事</b>：① 异常被计数（变得可见）；② **行为一个字不变**——
 * 异常原样重抛、拒绝（{@code BlockException}）不碰也不计数。
 */
class SentinelWatchdogSlotTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ResourceWrapper resource = new StringResourceWrapper("watchdog-probe", EntryType.IN);

    private SentinelWatchdogSlot watchdogWith(Throwable downstreamThrows) {
        SentinelWatchdogSlot.install(new GovernanceMetrics(meterRegistry));
        SentinelWatchdogSlot slot = new SentinelWatchdogSlot();
        slot.setNext(new ExplodingSlot(downstreamThrows));
        return slot;
    }

    private double internalErrors() {
        var counter = meterRegistry.find("coupon_sentinel_internal_error_total").counter();
        return counter == null ? 0 : counter.count();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("下游抛异常 → 计数一次，并**原样重抛**（不改变放不放行）")
    void downstreamError_isCountedAndRethrown() throws Throwable {
        RuntimeException boom = new IllegalStateException("模拟链内故障");
        SentinelWatchdogSlot slot = watchdogWith(boom);

        assertThatThrownBy(() -> slot.entry(null, resource, null, 1, false))
                .as("必须原样重抛：看门狗只负责让它可见，不负责改行为"
                        + "（外层 CtSph 仍会按它的规则吞掉并放行——那是框架的行为，不是我们的）")
                .isSameAs(boom);

        assertThat(internalErrors()).as("这一发请求的限流判定其实没生效，必须留下痕迹").isEqualTo(1.0);
    }

    @Test
    @DisplayName("下游抛 BlockException（拒绝）→ **不计数、也不改**：那是正常业务流，不是故障")
    void blockException_isNotAFailure() throws Throwable {
        FlowException block = new FlowException("测试租户");
        SentinelWatchdogSlot slot = watchdogWith(block);

        assertThatThrownBy(() -> slot.entry(null, resource, null, 1, false)).isSameAs(block);

        assertThat(internalErrors())
                .as("把拒绝也算成「链内异常」会让这个指标彻底失去意义——它要回答的是"
                        + "「有多少发请求的限流判定根本没跑到」，而不是「拒绝了多少发」")
                .isZero();
    }

    @Test
    @DisplayName("下游正常 → 一条也不计")
    void healthyChain_countsNothing() throws Throwable {
        SentinelWatchdogSlot.install(new GovernanceMetrics(meterRegistry));
        SentinelWatchdogSlot slot = new SentinelWatchdogSlot();
        slot.setNext(new ExplodingSlot(null));   // 不抛 = 正常通过

        slot.entry(null, resource, null, 1, false);

        assertThat(internalErrors()).isZero();
    }

    /** 模拟链上的下游槽位：按需抛指定异常 */
    private static final class ExplodingSlot extends AbstractLinkedProcessorSlot<Object> {

        private final Throwable toThrow;

        private ExplodingSlot(Throwable toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public void entry(Context context, ResourceWrapper resource, Object param, int count,
                          boolean prioritized, Object... args) throws Throwable {
            if (toThrow != null) {
                throw toThrow;
            }
        }

        @Override
        public void exit(Context context, ResourceWrapper resource, int count, Object... args) {
            // 本用例只验 entry 路径
        }
    }
}
