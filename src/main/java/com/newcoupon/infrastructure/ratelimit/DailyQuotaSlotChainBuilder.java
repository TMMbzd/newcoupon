package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slots.DefaultSlotChainBuilder;

/**
 * 把 {@link DailyQuotaSlot} 追加到 Sentinel 的标准处理链末尾（迁移第 5 步）。
 *
 * <p><b>为什么继承 {@code DefaultSlotChainBuilder} 而不是实现 {@code SlotChainBuilder}</b>：
 * 自定义构建器会**替换**默认的那一个（官方用 {@code @Spi(isDefault = true)} 标注默认实现），
 * 自己从头写一遍就等于把 Sentinel 的标准 slot（统计、流控、系统保护……）全丢了。
 * 继承 + 追加是最小改动。
 *
 * <p><b>为什么放在链尾</b>：让 Sentinel 自己的规则先判（它们是快路径、纯内存），
 * 我们的日配额要访问 Redis（慢路径）。**慢的放后面**——前面已经拒绝了就不必再去碰 Redis。
 *
 * <p>注册方式：{@code META-INF/services/com.alibaba.csp.sentinel.slotchain.SlotChainBuilder}。
 * 注意 SPI 的加载时机是"**首次访问资源时**"（由 {@code SphU} 的静态块触发），
 * 所以 {@link DailyQuotaSlot#install} 的注入必须在那之前完成——由 Spring 启动时做（见
 * {@link SentinelRuleConfig}）。
 */
public class DailyQuotaSlotChainBuilder extends DefaultSlotChainBuilder {

    @Override
    public ProcessorSlotChain build() {
        ProcessorSlotChain chain = super.build();
        chain.addLast(new DailyQuotaSlot());
        return chain;
    }
}
