package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **IP 维度精确限流**（迁移第 6 步，2026-10-08）——**官方做不到，所以自建**。
 *
 * <p><b>为什么不用官方的热点参数 {@code ParamFlowRule}</b>（1.8.10 源码逐行核实）：
 * <ul>
 *   <li>它用 <b>LRU</b> 统计（底层 {@code ConcurrentLinkedHashMap}，**未注册任何淘汰回调**），
 *       容量写死在 {@code ParameterMetric} 里：{@code BASE_PARAM_MAX_CAPACITY = 4000}、
 *       {@code TOTAL_MAX_CAPACITY = 200000}、线程计数 {@code 4000}，
 *       公式 {@code min(4000 × durationInSec, 200000)}。这些都是
 *       {@code private static final}，**没有任何 {@code csp.sentinel.*} 配置项、也没有 setter**；</li>
 *   <li>**淘汰即该值的令牌状态丢失**：下一次同值的请求走
 *       {@code putIfAbsent(value, new TokenUpdateStatus(now, maxCount - acquireCount))}，
 *       命中"空位"就直接 {@code return true}（**带着满额令牌放行**）→ 被限的 IP "复活"。
 *       注意这是**有条件的**：只有当"同一窗口内不同值的个数超过容量"真的发生淘汰时才出现——
 *       而对一个**请求方可控的无界维度**来说，这个条件恰恰是攻击者能主动制造的
 *       （多造几个假来源把自己挤出去即可）。所以"用 LRU 做精确防线"原理上就不成立。</li>
 * </ul>
 *
 * <p><b>官方另外两条"看起来能行"的路，核实后也不行</b>（都写在这里，免得以后有人再走一遍）：
 * <ul>
 *   <li>{@code FlowRule.limitApp} 确实能按 origin（可以把 IP 当 origin）**精确**计数
 *       （{@code ClusterBuilderSlot} 会为每个 origin 建节点）。但那些节点存在一个
 *       **无上限、无淘汰**的 map 里（官方注释自己写着 "origin 太多时内存要小心"），
 *       而且规则要**每个 IP 一条**、发布是全量替换——IP 无界 ⇒ 内存与规则表都无界。</li>
 *   <li>资源名正则（1.8.7+）匹配的是**资源名**，与来源无关；集群流控的
 *       {@code FlowRequestData} 里**没有 origin/IP 字段**（只有 flowId/count/priority），
 *       所以"按 IP"也上不了集群。</li>
 * </ul>
 *
 * <p><b>怎么挂上去</b>：实现官方 {@code ProcessorSlot} SPI，由
 * {@link OpenApiSlotChainBuilder} 追加到标准链的末尾，通过 {@code META-INF/services} 注册。
 * 与 {@link DailyQuotaSlot} 是同一个模式（那个自建 Slot 踩过的坑，本类全部适用）：
 * <ul>
 *   <li>SPI 类**不受 Spring 管理**，拿不到容器里的 bean → 用静态桥接
 *       （{@link #install}，由 {@link SentinelRuleConfig} 在启动时注入）；</li>
 *   <li>抛的异常**必须是 {@code BlockException} 子类**（见 {@link IpExactBlockException}）；</li>
 *   <li>末尾**必须 {@code fireEntry}**，否则整条链断在这里；</li>
 *   <li>Slot 是全局的，**别的资源必须原样放行**——这是本类第一件要做的事。</li>
 * </ul>
 *
 * <p><b>为什么用一个专属资源名而不是挂在 {@code open_api} 上</b>（本步的一个设计决定）：
 * IP 是**平台级**维度，"自我保护优先于商业约定"——它必须**排在租户维度之前**判。
 * 若挂在 {@code open_api} 上，链里的 {@code FlowSlot}（租户 QPS）会先跑，
 * 于是"被 IP 限掉的请求"也会算进租户用量：租户的对账数字会包含**平台根本没服务**的请求。
 * 想调整链内顺序就得重写整个 {@code DefaultSlotChainBuilder}（{@code addLast} 只能往后加），
 * 那是复制框架内部实现，代价远大于收益。所以改成**专属资源 + 控制器层里先 enter 一次**：
 * 顺序与迁移前完全一致，Slot 里只需按资源名判定，链的位置也不再重要。
 *
 * <p><b>IP 从哪来</b>：走 Sentinel 官方的参数通道——{@code SphU.entry(name, type, count, args...)}
 * 的 {@code args} 会**原样**传给 {@code ProcessorSlot.entry(..., Object... args)}
 * （字节码实测：{@code CtSph} 里传 {@code param = null}、{@code args} 透传；
 * 官方的 {@code ParamFlowSlot} 走的也是这条通道，它按 {@code rule.paramIdx} 取下标）。
 * 这里是**位置契约**：{@code args[0]} = 客户端 IP，由 {@link #CLIENT_IP_ARG_INDEX} 写明。
 *
 * <p><b>一条必须在引入热点参数之前先看这里的红线</b>：本项目**只往这条通道传了两个东西**
 * （{@code open_api} 传 appId、{@code open_api_ip} 传 IP），而官方唯一消费 args 的
 * {@code ParamFlowSlot} 在 {@code sentinel-parameter-flow-control} 模块里——
 * 我们的 pom **没有引它**，所以今天多传的参数不会被任何人读。
 * 但**一旦将来引入那个模块并给这些资源配 {@code ParamFlowRule}**，
 * {@code paramIdx} 的含义就与这里的下标耦合了（{@code paramIdx=0} 会取到 appId，
 * {@code -1} 取最后一个即 IP）——下标对不上不会报错，只会**限错对象**。
 */
public class IpExactSlot extends AbstractLinkedProcessorSlot<Object> {

    private static final Logger log = LoggerFactory.getLogger(IpExactSlot.class);

    /**
     * IP 维度的专属资源名。
     *
     * <p>**绝不能把 IP 拼进资源名**（如 {@code ip:1.2.3.4}）：Sentinel 会为每个资源名
     * 缓存一条处理链与一个 {@code ClusterNode}，而 IP 是无界的——那就是一个
     * 由请求方控制的内存泄漏（也正是 {@code ParamFlowRule} 存在的理由）。
     * 资源名固定、IP 走 {@code args}，这是本类与官方热点参数在设计上的唯一共识。
     */
    public static final String IP_GUARD_RESOURCE = "open_api_ip";

    /** {@code args} 里的位置契约：第 0 个参数 = 客户端 IP */
    public static final int CLIENT_IP_ARG_INDEX = 0;

    /** 由 Spring 启动时注入的检查器（SPI 类不受容器管理，只能用静态桥接） */
    private static volatile IpChecker checker;

    /** "检查器没装上"只报一次——它是接线错误，不是每请求都要刷的错误 */
    private static final AtomicBoolean MISSING_CHECKER_LOGGED = new AtomicBoolean();

    /** 安装实现——只应由 Spring 在启动时调用一次 */
    public static void install(IpChecker impl) {
        checker = impl;
    }

    @Override
    public void entry(Context context, ResourceWrapper resource, Object param, int count,
                      boolean prioritized, Object... args) throws Throwable {
        // ① 只对 IP 维度的专属资源生效：Slot 是全链共享的，别的资源必须原样放行
        if (IP_GUARD_RESOURCE.equals(resource.getName())) {
            IpChecker impl = checker;
            String ip = readIp(args);
            if (impl == null) {
                // 接线错误：SPI 加载的 Slot 拿到了 null 检查器 → IP 维度会**静默失效**。
                // 必须报出来，否则"限流没生效"会被当成"流量本来就小"。
                if (MISSING_CHECKER_LOGGED.compareAndSet(false, true)) {
                    log.error("IP 维度检查器未安装（SentinelRuleConfig 的静态桥接没生效），IP 维度将静默失效");
                }
            } else if (ip == null) {
                if (MISSING_CHECKER_LOGGED.compareAndSet(false, true)) {
                    log.error("IP 维度的 args[{}] 不是非空字符串——调用方没按契约传客户端 IP，"
                            + "IP 维度将静默失效（放行）", CLIENT_IP_ARG_INDEX);
                }
            } else {
                // 直接抛（checker 内部抛的是 BlockException 的子类）。
                // **不能改成"返回 boolean 再自己抛业务异常"**：那会被 CtSph 的
                // catch(Throwable) 吞掉并放行——见 IpExactBlockException。
                impl.check(ip);
            }
        }
        // ② **必须继续往下走**，否则整条链断在这里（官方 SPI 的硬要求）
        fireEntry(context, resource, param, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resource, int count, Object... args) {
        fireExit(context, resource, count, args);
    }

    private static String readIp(Object... args) {
        if (args == null || args.length <= CLIENT_IP_ARG_INDEX) {
            return null;
        }
        Object value = args[CLIENT_IP_ARG_INDEX];
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        return s;
    }

    /**
     * IP 检查的实现——由 Spring 提供（它需要限流器与指标）。
     *
     * <p>抽成接口是为了让本 Slot 只依赖"一个能检查的东西"，而不直接依赖
     * {@code RateLimiter} 与 {@code GovernanceMetrics}——这样 Slot 本身可以单测，
     * 换计数实现也不必动 Slot。
     */
    public interface IpChecker {

        /**
         * @throws IpExactBlockException 超限时抛出。
         *         <b>必须是 {@code BlockException} 的子类</b>——抛业务异常会被 Sentinel 静默吞掉并放行。
         */
        void check(String ip) throws IpExactBlockException;
    }
}
