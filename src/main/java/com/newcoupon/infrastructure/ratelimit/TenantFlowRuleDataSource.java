package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.datasource.AutoRefreshDataSource;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 租户配额规则的数据源：**MySQL → Sentinel 规则**（迁移第 3 步，2026-10-08）。
 *
 * <p><b>为什么必须自建</b>：官方没有 MySQL/RDBMS 数据源模块（官方点名的 pull 模式数据源是
 * 文件与 RDBMS，但只提供了文件实现）。而官方文档明确要求集群流控"必须对接动态规则源，
 * 否则无法正常使用"——所以这一层是迁移的必经之路。
 *
 * <p><b>为什么不用官方的 {@code InitFunc} SPI 注册</b>：那个 SPI 的执行时机是
 * "**首次访问资源时**"（由 {@code SphU} 的静态块触发），而不是 Spring 启动时——
 * 于是它拿不到 Spring 容器里的 bean（{@code OpenAppRepository} 等）。
 * 改用 Spring 的 {@code @PostConstruct} 注册（见 {@link SentinelRuleConfig}）。
 *
 * <p><b>刷新与"写后推"</b>：默认每 10 秒拉一次（官方默认 3000ms，对我们偏频繁——
 * 规则数 = 租户数，每次刷新是全量重建）。管理面改完配置后会**直接推一次**，
 * 所以正常情况下用户感知不到这个周期（"本实例立即、其他实例等刷新"，
 * 与项目现有租户配置缓存的模型一致）。
 *
 * <p><b>读失败怎么办——这是本类最需要守住的一点</b>：官方 {@code AutoRefreshDataSource}
 * 的刷新任务会 catch 所有异常，**只往它自己的 {@code ~/logs/csp/sentinel-record.log} 记一行 INFO**
 * （不进应用日志、无指标）。那意味着"规则源挂了"在我们的监控上**完全不可见**，
 * 而它保留旧规则继续工作（fail-static，这个行为本身是对的）。
 * 所以这里**自己 catch、自己打点、再抛出去**——让"拉取失败"变成看得见的事件。
 */
@Component
public class TenantFlowRuleDataSource
        extends AutoRefreshDataSource<List<TenantFlowRuleMapper.TenantQuota>, List<FlowRule>> {

    private static final Logger log = LoggerFactory.getLogger(TenantFlowRuleDataSource.class);

    /** 规则拉取失败的计数（供指标导出——它是"规则源不可达"的唯一可见线索） */
    private static final java.util.concurrent.atomic.AtomicLong LOAD_FAILURES =
            new java.util.concurrent.atomic.AtomicLong();

    private final OpenAppRepository appRepository;
    private final TenantConfigService tenantConfig;

    public TenantFlowRuleDataSource(
            OpenAppRepository appRepository,
            TenantConfigService tenantConfig,
            @Value("${coupon.sentinel.rule-refresh-ms:10000}") long refreshMs) {
        // Converter：原始形态（租户配额列表）→ Sentinel 规则列表。
        // 翻译逻辑在 TenantFlowRuleMapper 里（纯函数，单独测过）。
        super(quotas -> TenantFlowRuleMapper.map(quotas).rules(), refreshMs);
        this.appRepository = appRepository;
        this.tenantConfig = tenantConfig;
    }

    @Override
    public List<TenantFlowRuleMapper.TenantQuota> readSource() {
        try {
            List<TenantFlowRuleMapper.TenantQuota> quotas = new ArrayList<>();
            for (OpenApp app : appRepository.findAllApps()) {
                // 停用的租户不发规则：它们连签名认证都过不了，不需要限流保护。
                // （而且给它们发规则只是白白增加 FlowRuleManager 的全量重建成本。）
                if (!app.enabled()) {
                    continue;
                }
                quotas.add(new TenantFlowRuleMapper.TenantQuota(app.appId(), resolveQps(app)));
            }
            log.debug("租户配额规则回源：{} 个启用租户", quotas.size());
            return quotas;
        } catch (RuntimeException e) {
            // 打点 + 记应用日志，然后**抛出去**——让 AutoRefreshDataSource 走它的
            // "保留旧规则"路径（那是我们要的 fail-static 行为），同时让失败可见。
            LOAD_FAILURES.incrementAndGet();
            log.error("租户配额规则回源失败（本轮保留旧规则）：{}", e.toString());
            throw e;
        }
    }

    /**
     * 该租户的每秒上限：**租户配置优先，未配置回落登记表**。
     *
     * <p>与 {@code OpenApiQuotaFilter} 里原有的 {@code resolveLimit} 是同一套规则
     * （"0 = 不限"、"未配置用登记表的值"）——迁移期间两处并存，等过滤器整体切到 Sentinel 后
     * 旧的那份会删掉。
     */
    private int resolveQps(OpenApp app) {
        String configured = tenantConfig.get(app.appId(), TenantConfigKey.RATE_LIMIT_QPS);
        if (configured == null || configured.isBlank()) {
            return app.qpsLimit();   // 回落到登记表
        }
        return tenantConfig.getInt(app.appId(), TenantConfigKey.RATE_LIMIT_QPS);
    }

    /** 规则拉取累计失败次数——"规则源不可达"的可见线索（Sentinel 自己不产出这个） */
    public static long loadFailureCount() {
        return LOAD_FAILURES.get();
    }

    /**
     * 主动推一次（管理面改完配置后调用）。
     *
     * <p>不等下一个刷新周期——"改配置 → 立刻生效"是本项目对运维的承诺
     * （租户配置缓存也是这么做的）。
     */
    public void refreshNow() {
        try {
            getProperty().updateValue(loadConfig());
        } catch (Exception e) {
            // 推失败不回滚管理面操作：配置已经写进库了，下一个周期会自然收敛。
            // 但要让运维知道"这次没能立刻生效"。
            log.warn("主动推送规则失败（配置已落库，下个刷新周期会生效）：{}", e.toString());
        }
    }
}
