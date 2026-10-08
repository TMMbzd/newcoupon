package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.datasource.AutoRefreshDataSource;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
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

    /** 指标标签（ASCII 维度码）：规则源读不到 → 防线失效，必须与"流量本来就小"区分得开 */
    static final String METRIC_DIMENSION = "rule_source";

    private final OpenAppRepository appRepository;
    private final TenantConfigService tenantConfig;
    private final GovernanceMetrics metrics;

    public TenantFlowRuleDataSource(
            OpenAppRepository appRepository,
            TenantConfigService tenantConfig,
            @Value("${coupon.sentinel.rule-refresh-ms:10000}") long refreshMs,
            GovernanceMetrics metrics,
            OpenInterfaceCatalog interfaceCatalog) {
        // Converter：原始形态（租户配额列表）→ Sentinel 规则列表。
        // 翻译逻辑在 TenantFlowRuleMapper 里（纯函数，单独测过）。
        //
        // 在这里顺手上报"被丢弃的规则条数"（第 7 步）：mapper 已经把丢弃原因整理好了，
        // 而唯一的生产调用点原来把它**扔掉了**——那等于白预检。
        // 注意 lambda 捕获的是构造器参数（不是 this 的字段），所以不依赖字段赋值顺序。
        super(quotas -> {
            // **两类规则合并成一个列表再推**（第 8 步）：FlowRuleManager 只有一个属性位、
            // 且推送是**全量替换**——分成两个数据源必然互相冲掉（第 6 步的"单跑绿、全量红"就是这么来的）。
            TenantFlowRuleMapper.Mapping tenant = TenantFlowRuleMapper.map(quotas);
            InterfaceFlowRuleMapper.Mapping iface = interfaceCatalog.toRules();

            metrics.recordSentinelRulesDropped(tenant.dropped().size() + iface.dropped().size());

            List<FlowRule> merged = new ArrayList<>(tenant.rules());
            merged.addAll(iface.rules());
            return merged;
        }, refreshMs);
        this.appRepository = appRepository;
        this.tenantConfig = tenantConfig;
        this.metrics = metrics;
    }

    /**
     * 关掉父类的定时刷新线程（第 7 步补）。
     *
     * <p>少了它，每次上下文销毁都会留下一个每 10 秒查一次库的线程——**测试里尤其明显**
     * （共享容器之后同一 JVM 里有十几个上下文）。这是个 daemon 线程、不影响进程退出，
     * 但"用完就关"是数据源这种有生命周期的对象该有的纪律。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() throws Exception {
        close();
    }

    /**
     * **把本数据源的推送目标改成 JVM 级的共享槽位**（第 6 步全量测试暴露后修正）。
     *
     * <p>父类 {@code AbstractDataSource} 的 {@code property} 是 {@code protected final}，
     * 换不掉；但父类的定时刷新任务走的是 {@code getProperty()}（字节码实测：
     * {@code invokevirtual getProperty → SentinelProperty.updateValue}），
     * 所以**重写这个方法**就能让"周期刷新"与"主动推送"两条路都进同一个槽位。
     *
     * <p>为什么必须这么做：{@code FlowRuleManager} 只认一个属性，多上下文（测试里十几个）
     * 各自的注册会把别人摘掉——于是"数据源读得到、规则却是空的"，且**取决于上下文创建顺序**。
     * 详见 {@link SharedRuleProperty} 的类注释。
     */
    @Override
    public com.alibaba.csp.sentinel.property.SentinelProperty<List<FlowRule>> getProperty() {
        return SharedRuleProperty.instance();
    }

    /**
     * 读一轮租户配额。
     *
     * <p><b>第 7 步加的一道守卫，理由值得单独说</b>：Sentinel 的
     * {@code AutoRefreshDataSource} 只在 {@code loadConfig()} **抛异常**时才保留旧规则
     * （fail-static）；**返回空列表或 null 会被它一路推到 {@code FlowRuleManager} 把规则整表清空**
     * （源码级核实 + 在 1.8.10 jar 上实跑：rules 2 → 0）。
     *
     * <p>而"登记表一行都读不到"这种情况，在旧写法里返回的恰好是空列表且不抛异常——
     * 后果是**所有租户静默变成不限流**，应用日志里只有一行 debug。
     * 这是整套机制里唯一一处"错一下就是全体失守"的路径，所以这里**宁可报错**：
     * 抛出去就走 fail-static 保旧规则，同时把这次"防线失效"打进指标。
     *
     * <p><b>刻意不拦的两种"合法的空"</b>（拦它们反而更糟，见 {@code TenantFlowRuleDataSourceTest}）：
     * ① 租户行存在但**全部停用**——那意味着所有调用方都过不了验签，规则有无都一样，
     * 而拦住它会把"紧急停掉所有接入方"这个运维操作冻住；② 租户配额**全为 0（不限）**——
     * 那是运维的正常操作，"以为关了其实没关"比不关更糟。
     */
    @Override
    public List<TenantFlowRuleMapper.TenantQuota> readSource() {
        try {
            List<com.newcoupon.domain.open.OpenApp> apps = appRepository.findAllApps();
            if (apps.isEmpty()) {
                throw new IllegalStateException(
                        "登记表读不到任何接入方：拒绝把规则清空（保留上一轮规则）。"
                                + "若这是真的（全新环境），去建一个接入方即可");
            }
            List<TenantFlowRuleMapper.TenantQuota> quotas = new ArrayList<>();
            for (OpenApp app : apps) {
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
            // 第 7 步补：以前只有一条 ERROR 日志与一个**没人消费**的静态计数器。
            // 现在接进治理指标——"规则源读不到导致限流全失效"必须能在仪表盘上看见。
            metrics.recordLimitFailOpen(METRIC_DIMENSION);
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
     * 收到"配额可能变了"的事件时主动推一次（2026-10-08）。
     *
     * <p>覆盖三个入口：新建接入方、启停接入方、改租户配置。
     * 少了它，"新建的租户在最多 10 秒内不受限"——那是实测出来的真实缺口。
     */
    /**
     * 开放面路径模式目录就绪 → 立刻重推一次。
     *
     * <p>首次推送发生在 {@code SentinelRuleConfig} 的构造器里，那时 Spring MVC 的 handler
     * 还没探测完、目录是空的——不补这一推，接口维度要等下一个刷新周期（默认 10 秒）才生效。
     */
    @org.springframework.context.event.EventListener
    public void onInterfaceCatalogReady(OpenInterfaceCatalog.Event event) {
        log.debug("开放面路径模式目录就绪（{} 条），重推规则", event.patternCount());
        refreshNow();
    }

    @org.springframework.context.event.EventListener
    public void onQuotaChanged(TenantQuotaChangedEvent event) {
        log.debug("租户配额变更（{}），主动推送规则", event.reason());
        refreshNow();
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
