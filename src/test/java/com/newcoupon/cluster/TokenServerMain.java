package com.newcoupon.cluster;

import com.alibaba.csp.sentinel.cluster.server.ClusterTokenServer;
import com.alibaba.csp.sentinel.cluster.server.SentinelDefaultTokenServer;
import com.alibaba.csp.sentinel.cluster.server.config.ClusterServerConfigManager;
import com.alibaba.csp.sentinel.cluster.flow.rule.ClusterFlowRuleManager;
import com.alibaba.csp.sentinel.cluster.server.config.ServerFlowConfig;
import com.alibaba.csp.sentinel.cluster.server.config.ServerTransportConfig;
import com.newcoupon.infrastructure.ratelimit.TenantFlowRuleMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **Token Server**（第 9 步，2026-10-08）——集群流控的服务端，独立进程。
 *
 * <p><b>它是什么</b>：一个 Netty 服务，替所有应用实例维护**全局**的窗口计数。
 * 应用侧（客户端）每次要通过一条 {@code clusterMode} 规则时，都把
 * {@code (flowId, count)} 发给它，由它回答"还能不能再放一个"。于是 k 个实例合计不超过阈值
 * ——这是单机统计做不到的事（各限各的 = k 倍）。
 *
 * <p><b>为什么它自己读库</b>：官方部署里 flowId 与集群规则都是**控制台**推给 Token Server 的
 * （1.8.10 的控制台甚至没有把集群规则推给服务端的能力），我们没有控制台，
 * 所以两端都从**同一个 MySQL** 用**同一个纯函数**算出规则与 flowId
 * （{@link TenantFlowRuleMapper#map} + {@code ClusterFlowId}）——两端天然一致，不需要任何同步通道。
 *
 * <p><b>为什么它在 test 作用域</b>：它是独立进程（约 65MB），不属于应用的可部署产物。
 * 放在 {@code src/test/java} 里可以让生产 jar 干净，验证脚本用 test classpath 起它。
 * **生产形态**应该是独立部署 + 独立规则通道，这一点写在 docs/S3 §10.8 里。
 *
 * <p><b>两条纪律</b>（与客户端规则源同款）：
 * ① 读库失败时**不推**（保留旧规则）——服务端收到空列表会把规则与统计一起清掉；
 * ② 推送前跑 {@code isValidRule} 预检并把丢弃数打出来（Sentinel 自己只写它的日志文件）。
 *
 * <p>用法：{@code java -cp <classpath> com.newcoupon.cluster.TokenServerMain [port] [namespace]}
 * 环境变量：{@code CLUSTER_DB_URL} / {@code CLUSTER_DB_USER} / {@code CLUSTER_DB_PASSWORD}
 */
public final class TokenServerMain {

    private static final Logger log = LoggerFactory.getLogger(TokenServerMain.class);

    /** 规则轮询周期：与客户端规则源同一个量级（配置改动最多这么久传到服务端） */
    private static final long REFRESH_MS = 10_000L;

    private TokenServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18730;
        String namespace = args.length > 1 ? args[1] : "newcoupon";
        String dbUrl = envOr("CLUSTER_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/newcoupon?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai");
        String dbUser = envOr("CLUSTER_DB_USER", "root");
        String dbPassword = envOr("CLUSTER_DB_PASSWORD", "root123456");

        // ① 传输配置（端口）：默认就是 18730，这里显式写出来便于排障
        ClusterServerConfigManager.loadGlobalTransportConfig(new ServerTransportConfig(port, 600));

        // ①' **flow 配置必须显式加载**——不加载的话 ClusterServerConfigManager 里的
        //     exceedCount / maxOccupyRatio / intervalMs / sampleCount 全是 **0**（静态字段默认值），
        //     而服务端算的是 `阈值 = 规则count × exceedCount` → **0** → **每一发 token 请求都被拒**。
        //     症状极具迷惑性：客户端拿到的是 BLOCKED（429），看起来像"限流生效了、只是太严"，
        //     而不是"配置没加载"。实测：配额 10 的租户，20 发只过 1 个（那 1 个还是另一台实例
        //     在规则还没到位时走本地回退放过去的）。
        ServerFlowConfig flowConfig = new ServerFlowConfig(namespace)
                .setExceedCount(1.0)        // 阈值系数：1.0 = 规则里的 count 就是集群总量
                .setMaxOccupyRatio(1.0)     // 单个客户端最多能占多少（1.0 = 不额外限制）
                .setIntervalMs(1000)        // 与客户端同口径的**秒级**滑动窗口
                .setSampleCount(10);        // 1 秒切 10 个桶（客户端默认也是这个）
        ClusterServerConfigManager.loadGlobalFlowConfig(flowConfig);
        ClusterServerConfigManager.loadFlowConfig(namespace, flowConfig);
        log.info("服务端 flow 配置已加载：namespace={} exceedCount={} intervalMs={} sampleCount={}",
                namespace, flowConfig.getExceedCount(), flowConfig.getIntervalMs(),
                flowConfig.getSampleCount());

        // ①' namespace 必须**先注册**进服务端的规则表：
        //    `ClusterFlowRuleManager.loadRules(namespace, rules)` 对一个**没注册过的 namespace
        //    什么都不做**（源码：PROPERTY_MAP.get(namespace) 为 null 就直接 return，连日志都没有）。
        //    第一版就是这么错的：日志打着"已装载 39 条"，实际一条都没进去，
        //    客户端每次要令牌都被告知"没有这条规则"→ **静默回退到单机限制**（合计配额翻倍）。
        ClusterFlowRuleManager.registerPropertyIfAbsent(namespace);

        // ② 先装一次规则，再起服务：**顺序很重要**——先起服务的话，客户端可能在
        //    "服务已通、规则还没装"的窗口里拿到"没有这条规则"，从而静默回退本地。
        loadRules(namespace, dbUrl, dbUser, dbPassword);

        // ③ 起服务（内部是 Netty；bind 失败会自己重试几次——失败信息只在它自己的日志里，
        //    所以这里把结果也打一行出来）
        ClusterTokenServer server = new SentinelDefaultTokenServer();
        server.start();
        log.warn("Token Server 已启动：port={} namespace={}", port, namespace);

        // ④ 周期轮询规则（读失败就不推，保留旧规则）
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "token-server-rule-refresh");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> loadRules(namespace, dbUrl, dbUser, dbPassword),
                REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS);

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.warn("Token Server 正在停止……");
            timer.shutdownNow();
            stop.countDown();
        }, "token-server-shutdown"));
        stop.await();
    }

    /**
     * 从库里读租户配额 → 翻译成集群规则 → 推给服务端的规则表。
     *
     * <p>与客户端用**同一个** {@link TenantFlowRuleMapper}（纯函数）与同一个 flowId 生成方式，
     * 所以两端对"哪个 flowId 对应哪条规则"必然一致——这正是官方要靠控制台才能做到的事。
     */
    private static void loadRules(String namespace, String url, String user, String password) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT a.app_id, a.qps_limit, c.config_value
                     FROM open_app a
                     LEFT JOIN open_app_config c
                       ON c.app_id = a.app_id AND c.config_key = 'rate_limit.qps'
                     WHERE a.status = 1
                     """)) {
            List<TenantFlowRuleMapper.TenantQuota> quotas = new ArrayList<>();
            while (rs.next()) {
                String configured = rs.getString("config_value");
                int qps = (configured == null || configured.isBlank())
                        ? rs.getInt("qps_limit")
                        : Integer.parseInt(configured.trim());
                quotas.add(new TenantFlowRuleMapper.TenantQuota(rs.getString("app_id"), qps));
            }

            var mapping = TenantFlowRuleMapper.map(quotas, true /* 集群：租户规则带 GLOBAL 配置 */);
            if (!mapping.dropped().isEmpty()) {
                log.warn("规则预检丢弃 {} 条（它们不会生效）：{}", mapping.dropped().size(), mapping.dropped());
            }
            if (mapping.rules().isEmpty()) {
                log.warn("这一轮没有集群规则（库里没有任何启用且配额>0 的租户）——保留上一轮规则");
                return;
            }
            ClusterFlowRuleManager.loadRules(namespace, mapping.rules());

            // **回读校验**：loadRules 对没注册过的 namespace 会静默什么都不做，
            // 所以"我调过了"不等于"规则进去了"。回读一次，把这件事从静默变成响的。
            java.util.Set<Long> loaded = ClusterFlowRuleManager.getFlowIdSet(namespace);
            if (loaded == null || loaded.isEmpty()) {
                log.error("集群规则**没有装载成功**：namespace={} 的 flowId 集合为空——"
                        + "客户端会被告知「没有这条规则」并静默回退到单机限制。"
                        + "检查 namespace 是否已 registerPropertyIfAbsent", namespace);
            } else {
                log.info("集群规则已装载：namespace={} 共 {} 条（flowId 示例={}）", namespace,
                        loaded.size(), loaded.iterator().next());
            }
        } catch (Exception e) {
            // **读失败不推**：服务端收到空列表会把规则与统计一起清掉——那是"全部放行"，
            // 比"用旧规则"危险得多。这条纪律与客户端规则源一致。
            log.error("读库/装载集群规则失败（保留上一轮规则）：{}", e.toString());
        }
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
