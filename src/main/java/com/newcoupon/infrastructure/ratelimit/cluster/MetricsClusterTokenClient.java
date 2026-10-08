package com.newcoupon.infrastructure.ratelimit.cluster;

import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.client.ClusterTokenClient;
import com.alibaba.csp.sentinel.cluster.client.DefaultClusterTokenClient;
import com.alibaba.csp.sentinel.cluster.TokenServerDescriptor;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 集群 token 客户端的**装饰器**：把"集群降级"变成看得见的事件（第 9 步，2026-10-08）。
 *
 * <p><b>为什么必须有人干这件事</b>：{@code ClusterFlowConfig.fallbackToLocalWhenFail} 默认 true，
 * 而"回退本地"的意思是 **每个实例各限一份**——两个实例合计就是 2 倍配额，**跨实例精确这件事
 * 当场失效**。更糟的是它**完全没有痕迹**：连不上 / 超时 / 服务端没有这条规则 / 请求非法，
 * 五种 TokenResultStatus 全都静默走回退，只写进 Sentinel 自己的日志文件
 * （{@code ~/logs/csp/}），不进应用日志、更没有指标。
 * 于是"集群流控正在工作"与"集群流控早就退化成单机了"在监控上长得一模一样。
 *
 * <p>这正是第 7 步立下的那条规矩的续集：**防线失效必须可见**。
 *
 * <p><b>它怎么被用上</b>：实现官方 SPI {@code ClusterTokenClient}，并在**本应用自己的**
 * {@code META-INF/services} 里注册。注意 {@code SpiLoader.loadFirstInstance()} 取的是
 * <b>classList 的第一个</b>（类路径资源枚举顺序，**不是** {@code @Spi(order)}）——
 * 所以"装饰器到底有没有生效"必须**在启动时断言**（见 {@code SentinelClusterClientConfig}，
 * 并且做成一条 gauge 挂在面板上），否则它会以最安静的方式失效。
 *
 * <p><b>实现约束</b>：它在**请求热路径**上被调用，所以只做一次计数器自增与一次取值比较，
 * 不做任何分配、不打印日志（日志由启动期断言负责）。
 */
public class MetricsClusterTokenClient implements ClusterTokenClient {

    private static final Logger log = LoggerFactory.getLogger(MetricsClusterTokenClient.class);

    /** 由启动期注入（SPI 类不受 Spring 管理，只能用静态桥接——与自建 Slot 同一个套路） */
    private static volatile GovernanceMetrics metrics;

    public static void install(GovernanceMetrics impl) {
        metrics = impl;
        log.info("集群 token 客户端装饰器已装载（集群降级会计入 coupon_sentinel_cluster_token_fallback_total）");
    }

    /** 真正的客户端：SPI 只实例化**我们**这一个，由我们持有一个默认实现（不去递归加载 SPI） */
    private final ClusterTokenClient delegate = new DefaultClusterTokenClient();

    /**
     * 前若干次调用打一行诊断（flowId/状态/服务端算出的剩余额度）。
     *
     * <p>为什么值得留：集群出问题时，"到底是没连上、没规则、还是服务端真的在拒"这三件事
     * 从客户端这边**看不出来**（都是 429）。有了这几行，第一屏日志就能定性。
     * 只在启动初期打（次数上限），不影响热路径。
     */
    private static final int DIAG_LIMIT = 40;
    private static final java.util.concurrent.atomic.AtomicInteger DIAG_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public TokenResult requestToken(Long flowId, int acquireCount, boolean prioritized) {
        TokenResult result = delegate.requestToken(flowId, acquireCount, prioritized);
        if (DIAG_COUNT.incrementAndGet() <= DIAG_LIMIT) {
            log.info("集群 DIAG：flowId={} status={} remaining={}",
                    flowId, result == null ? "null" : result.getStatus(),
                    result == null ? "?" : result.getRemaining());
        }
        return recordDegradation(result);
    }

    @Override
    public TokenResult requestParamToken(Long ruleId, int acquireCount, Collection<Object> params) {
        return recordDegradation(delegate.requestParamToken(ruleId, acquireCount, params));
    }

    @Override
    public TokenResult requestConcurrentToken(String clientAddress, Long flowId, int acquireCount) {
        return recordDegradation(delegate.requestConcurrentToken(clientAddress, flowId, acquireCount));
    }

    @Override
    public void releaseConcurrentToken(Long tokenId) {
        delegate.releaseConcurrentToken(tokenId);
    }

    @Override
    public TokenServerDescriptor currentServer() {
        return delegate.currentServer();
    }

    @Override
    public void start() throws Exception {
        delegate.start();
    }

    @Override
    public void stop() throws Exception {
        delegate.stop();
    }

    @Override
    public int getState() {
        return delegate.getState();
    }

    /**
     * 只有"正常"的三种状态不算降级：OK（放行）、BLOCKED（拒绝）、SHOULD_WAIT（排队）。
     * 其余（FAIL / TOO_MANY_REQUEST / NO_RULE_EXISTS / BAD_REQUEST / NOT_AVAILABLE…）
     * 都会让 {@code FlowRuleChecker} **静默回退到本地单机限制**——那正是要计数的时刻。
     */
    private TokenResult recordDegradation(TokenResult result) {
        GovernanceMetrics impl = metrics;
        Integer status = result == null ? null : result.getStatus();
        if (impl == null || status == null) {
            return result;
        }
        switch (status) {
            case TokenResultStatus.OK, TokenResultStatus.BLOCKED, TokenResultStatus.SHOULD_WAIT -> {
                // 正常路径：什么都不做（这是热路径，不要在这里打日志）
            }
            default -> impl.recordClusterTokenFallback(statusName(status));
        }
        return result;
    }

    /** 状态码 → 有界标签（**不用 toString**：标签必须是长期契约，也要防未来新增状态把基数打爆） */
    private static String statusName(int status) {
        return switch (status) {
            case TokenResultStatus.FAIL -> "fail";
            case TokenResultStatus.TOO_MANY_REQUEST -> "too_many";
            case TokenResultStatus.NO_RULE_EXISTS -> "no_rule";
            case TokenResultStatus.NO_REF_RULE_EXISTS -> "no_ref_rule";
            case TokenResultStatus.BAD_REQUEST -> "bad_request";
            case TokenResultStatus.NOT_AVAILABLE -> "not_available";
            default -> "other";
        };
    }
}
