package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slots.DefaultSlotChainBuilder;

/**
 * 把**自建的 Slot** 追加到 Sentinel 标准处理链的末尾（第 5 步加日配额，第 6 步加 IP 维度）。
 *
 * <p><b>为什么继承 {@code DefaultSlotChainBuilder} 而不是实现 {@code SlotChainBuilder}</b>：
 * 自定义构建器会**替换**默认的那一个（官方用 {@code @Spi(isDefault = true)} 标注默认实现），
 * 自己从头写一遍就等于把 Sentinel 的标准 slot（统计、流控、系统保护……）全丢了。
 * 继承 + 追加是最小改动。
 *
 * <p><b>为什么放在链尾</b>：让 Sentinel 自己的规则先判（它们是快路径、纯内存），
 * 我们的自建检查要访问 Redis（慢路径）。**慢的放后面**——前面已经拒绝了就不必再去碰 Redis。
 *
 * <p><b>为什么两个自建 Slot 都在链尾却不会互相干扰</b>：它们各自只认自己的资源名
 * （{@code open_api} / {@code open_api_ip}），其余资源原样放行。
 * 这也说明"链上的位置"对它们不重要——重要的是**控制器层先 enter 哪个资源**
 * （顺序问题在 {@code OpenApiQuotaFilter} 里解决，见 {@link IpExactSlot} 的类注释）。
 *
 * <p>注册方式：{@code META-INF/services/com.alibaba.csp.sentinel.slotchain.SlotChainBuilder}。
 * 注意 SPI 的加载时机是"**首次访问资源时**"（由 {@code SphU} 的静态块触发），
 * 所以 {@link DailyQuotaSlot#install} / {@link IpExactSlot#install} 的注入必须在那之前完成
 * ——由 Spring 启动时做（见 {@link SentinelRuleConfig}）。
 */
public class OpenApiSlotChainBuilder extends DefaultSlotChainBuilder {

    @Override
    public ProcessorSlotChain build() {
        ProcessorSlotChain chain = super.build();
        chain.addLast(new DailyQuotaSlot());
        chain.addLast(new IpExactSlot());
        return chain;
    }
}
