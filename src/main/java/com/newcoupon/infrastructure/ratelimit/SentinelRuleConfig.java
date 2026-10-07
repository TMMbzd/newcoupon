package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 把自建数据源接到 Sentinel 的规则管理器上（迁移第 3 步，2026-10-08）。
 *
 * <p><b>为什么用 {@code @Configuration} 的构造器，而不是官方的 {@code InitFunc} SPI</b>：
 * 那个 SPI 由 {@code SphU} 的静态块在"首次访问资源时"触发——**不是 Spring 启动时**，
 * 而且它拿不到 Spring 容器里的 bean（我们需要的 {@code OpenAppRepository}、
 * {@code TenantConfigService} 都在容器里）。用构造器注入可以保证：
 * ① 启动期就完成注册与首次加载；② 依赖的 bean 一定已经就绪。
 *
 * <p><b>为什么只注册一次</b>：{@code FlowRuleManager} 内部只有一个 {@code SentinelProperty}
 * 槽位，**多次注册会互相顶掉**（官方文档与源码一致）。所以这里刻意不提供任何"再注册一个源"
 * 的入口——要加规则源，应该扩展 {@link TenantFlowRuleDataSource} 的 {@code readSource}，
 * 而不是再挂一个 property。
 */
@Configuration
public class SentinelRuleConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleConfig.class);

    public SentinelRuleConfig(TenantFlowRuleDataSource dataSource,
                              RedisDailyQuotaChecker dailyQuotaChecker) {
        // 把日配额检查器装进 Slot（SPI 加载的 Slot 不受 Spring 管理，只能用静态桥接——
        // 这一步**必须**在首次访问资源之前完成，否则 Slot 拿到的是 null、日配额静默失效）
        DailyQuotaSlot.install(dailyQuotaChecker);

        // ① 注册（此后 dataSource 每次刷新都会把新规则推进 FlowRuleManager）
        FlowRuleManager.register2Property(dataSource.getProperty());

        // ② 立刻加载一次——不等第一个刷新周期（默认 10 秒后）。
        // 否则应用刚起来的这 10 秒里，**所有租户配额都是"无规则=全放行"**。
        dataSource.refreshNow();

        log.info("租户配额规则源已注册：每 {} ms 刷新一次（管理面改动会主动推）",
                "coupon.sentinel.rule-refresh-ms");
    }
}
