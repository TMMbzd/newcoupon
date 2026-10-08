package com.newcoupon.infrastructure.ratelimit.cluster;

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.client.ClientConstants;
import com.alibaba.csp.sentinel.cluster.client.ClusterTokenClient;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientAssignConfig;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientConfig;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientConfigManager;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 集群流控的**客户端接线**（第 9 步，2026-10-08）——默认关。
 *
 * <p><b>为什么需要集群</b>：Sentinel 的统计默认在 JVM 内，k 个副本 = k 份额度。
 * 要"租户配额跨实例精确"（对账要用），只能让所有实例向一个 Token Server 要令牌：
 * 服务端维护全局窗口计数，客户端拿到的是**剩余额度**。
 *
 * <p><b>为什么这段代码必须手写、而且顺序不能换</b>（源码级核实，三步有硬约束）：
 * <ol>
 *   <li>{@code applyNewConfig}：{@code ClusterClientConfig} 只有 {@code requestTimeout} 一个字段，
 *       **默认 20ms**——对跨进程调用太紧，超时就会走降级。这里默认给 200ms。</li>
 *   <li>{@code applyNewAssignConfig}：**服务端地址在这里**（不在集群配置里），
 *       而且**必须在客户端实例被创建之前**应用——否则实例构造时读到空 host，
 *       **不会建连接**，而且不报错。</li>
 *   <li>{@code ClusterStateManager.setToClient()}：这是**唯一**真正启动 Netty 客户端的地方
 *       （库不会自动切）。不调的话 mode 一直是 -1，所有 {@code clusterMode} 规则
 *       **静默回退到本地单机限制**——也就是"集群流控配了等于没配"，且没有任何征兆。</li>
 * </ol>
 *
 * <p><b>三处"静默"被我们改成了响的</b>：
 * ① SPI 缺失（{@code setToClient()} 返回 false）→ ERROR 日志；
 * ② 装饰器没生效（SPI 取的是类路径里第一个实现，**不保证是我们**）→ ERROR 日志 + gauge；
 * ③ 运行期降级（连不上/超时/服务端没这条规则）→ 装饰器计数
 * （见 {@link MetricsClusterTokenClient}）。
 * 再配上两条 gauge（客户端状态、集群模式），面板上就能回答"集群到底有没有在工作"。
 */
@Configuration
public class SentinelClusterClientConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelClusterClientConfig.class);

    private final boolean enabled;

    public SentinelClusterClientConfig(
            @Value("${coupon.sentinel.cluster.enabled:false}") boolean enabled,
            @Value("${coupon.sentinel.cluster.server-host:127.0.0.1}") String host,
            @Value("${coupon.sentinel.cluster.server-port:18730}") int port,
            @Value("${coupon.sentinel.cluster.request-timeout-ms:200}") int requestTimeoutMs,
            @Value("${coupon.sentinel.cluster.namespace:newcoupon}") String namespace,
            GovernanceMetrics governanceMetrics,
            MeterRegistry meterRegistry) {
        this.enabled = enabled;

        // 装饰器要**无条件**装上：SPI 可能在任何时刻实例化它（我们控制不了那一刻）
        MetricsClusterTokenClient.install(governanceMetrics);

        // 两条 gauge 用惰性 lambda：**取值的时刻**才碰 SPI，避免"构造器里就把客户端实例造出来"
        // 这个顺序陷阱（客户端实例一旦造出来，后面再配服务端地址就不生效了）。
        Gauge.builder("coupon_sentinel_cluster_client_state",
                        () -> enabled ? TokenClientProvider.getClient().getState()
                                : ClientConstants.CLIENT_STATUS_OFF)
                .description("集群 token 客户端状态（0=OFF 1=PENDING 2=STARTED）。"
                        + "**只要不是 2，clusterMode 规则就在走本地单机限制**——面板上这条必须盯着")
                .register(meterRegistry);
        Gauge.builder("coupon_sentinel_cluster_mode",
                        () -> ClusterStateManager.getMode())
                .description("集群模式（-1=未启动 0=客户端 1=服务端）。配了集群却一直是 -1 说明 "
                        + "setToClient() 没生效（或 SPI 缺失）")
                .register(meterRegistry);
        Gauge.builder("coupon_sentinel_cluster_decorator_active",
                        () -> enabled ? (TokenClientProvider.getClient() instanceof MetricsClusterTokenClient ? 1 : 0) : 0)
                .description("集群降级计数的装饰器是否生效（1=生效）。SPI 取的是类路径里第一个实现，"
                        + "**不保证是我们**——所以这条 gauge 是那个断言的可见形式")
                .register(meterRegistry);

        if (!enabled) {
            log.info("集群流控未启用（默认关）：租户配额按**单机**计算——k 个实例合计是 k 倍配额。"
                    + "要跨实例精确，把 coupon.sentinel.cluster.enabled 打开并起一个 Token Server");
            return;
        }

        // ---- ⓪ namespace：**两端必须一致** ----
        // 客户端 ping 时上报的是 "我是哪个 namespace 的客户端"，服务端只认自己装载了规则的
        // 那个 namespace——对不上就是 NO_RULE_EXISTS → **静默降级**。
        // 默认取进程名（sun.java.command 之类），在"两个实例由同一个脚本用不同端口拉起"的场景里
        // 往往相同、但没有任何保证；所以这里显式指定，并与 Token Server 用同一个值。
        com.alibaba.csp.sentinel.cluster.registry.ConfigSupplierRegistry
                .setNamespaceSupplier(() -> namespace);

        // ---- ① 请求超时（默认 20ms 太紧；超时=降级=退化成单机限制）----
        ClusterClientConfigManager.applyNewConfig(
                new ClusterClientConfig().setRequestTimeout(requestTimeoutMs));
        // ---- ② 服务端地址（必须在客户端实例被创建之前）----
        ClusterClientConfigManager.applyNewAssignConfig(new ClusterClientAssignConfig(host, port));
        // ---- ③ 切换状态：唯一真正启动 Netty 客户端的地方 ----
        boolean switched = ClusterStateManager.setToClient();
        if (!switched) {
            log.error("集群客户端 SPI 缺失（ClusterTokenClient 没在 classpath 上）："
                    + "所有 clusterMode 规则会**静默回退到单机限制**，合计配额会随实例数翻倍");
        }
        ClusterTokenClient client = TokenClientProvider.getClient();
        if (!(client instanceof MetricsClusterTokenClient)) {
            log.error("集群 token 装饰器**没有生效**（实际装载的是 {}）：集群降级计数不可用，"
                    + "也就是「集群退化成单机」这件事没人看得见。检查 META-INF/services 里"
                    + "ClusterTokenClient 的类路径顺序（SPI 取的是第一个，不是 @Spi(order)）",
                    client.getClass().getName());
        }
        log.warn("集群流控已启用：server={}:{} namespace={} timeout={}ms mode={} clientState={} "
                        + "currentServer={}——clientState 要变成 2(STARTED) 才算真的连上，"
                        + "currentServer 为 null 说明构造期就没读到服务端地址（连接根本没建）",
                host, port, namespace, requestTimeoutMs, ClusterStateManager.getMode(),
                client.getState(), client.currentServer());
    }

    /**
     * 停机时**把集群状态切回去**。
     *
     * <p>两个理由：① 生产上是体面的关停（不再向 Token Server 要令牌）；
     * ② 测试里尤其重要——{@code ClusterStateManager} 是 **JVM 级静态状态**，
     * 不切回去会让同一个 JVM 里后面的上下文继续以为自己是集群客户端
     * （第 8 步刚被同类问题咬过一次：自己打开的 JVM 级开关，必须自己关）。
     */
    @PreDestroy
    public void shutdown() {
        if (enabled) {
            ClusterStateManager.applyState(ClusterStateManager.CLUSTER_NOT_STARTED);
        }
    }
}
