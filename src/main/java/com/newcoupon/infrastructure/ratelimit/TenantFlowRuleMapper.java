package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slots.block.ClusterRuleConstant;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.ClusterFlowConfig;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleUtil;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把"租户配额"翻译成 Sentinel 的 {@link FlowRule}（2026-10-08，迁移第 3 步）。
 *
 * <p><b>为什么需要一个纯函数来做这件事</b>：翻译规则是这次迁移里最容易出错、
 * 又最需要反复验证的一环——判据、边界、非法值处理都要能单独测。
 * 把它从"数据源拉取"里拆出来，就能用普通单元测试覆盖所有分支，
 * 不必每次都起 Spring 上下文与容器。
 *
 * <p><b>规则怎么映射</b>（这是设计决策，不是实现细节）：
 * <ul>
 *   <li><b>租户维度</b>：{@code resource=open_api}（一个**聚合资源**）、
 *       {@code limitApp=appId}、{@code count=该租户的每秒上限}。
 *       <br>为什么需要"聚合资源"：本项目现有的语义是"**该租户跨所有开放面接口的总 QPS**"
 *       （原来的实现是单个 Redis 键 {@code c:open:qps:{appId}}）。
 *       而 Sentinel 的规则粒度是 {@code (resource, origin)}——如果按"每个接口各自一条"，
 *       语义会变成"每个接口各自限这么多"，那是**另一个语义**，会让一个租户的总量
 *       随接口数放大。所以额外埋一个聚合资源来承载它。</li>
 *   <li><b>接口维度</b>：{@code resource=<接口路径>}、{@code limitApp="default"}、
 *       {@code count=平台阈值}。接口路径由适配器自动成为资源名（第 0 步实测确认），
 *       不需要我们命名。</li>
 * </ul>
 *
 * <p><b>{@code count <= 0} 不生成规则</b>——这是一个**语义陷阱**，必须写清楚：
 * 本项目的契约是"0 = 不限"（见 {@link RateLimiter}），而 Sentinel 的
 * {@code count=0} 意味着"**全部拒绝**"（源码 {@code DefaultController.canPass}：
 * {@code curCount + acquireCount > count} 即拒）。方向正好相反。
 * 所以必须**不发这条规则**，而不是"发一条 count=0 的"。
 *
 * <p><b>非法规则要预检</b>：Sentinel 对过不了 {@code isValidRule} 的规则**只写一行 WARN 就丢弃**
 * （而且写进它自己的 {@code ~/logs/csp/sentinel-record.log}，**不进应用日志**）。
 * 那意味着"管理面显示保存成功、运行时静默无效"——正是本项目一直在防的问题类型。
 * 所以这里先预检，把丢弃的条数**报出来**（由调用方打点/告警），而不是让它静默消失。
 */
public final class TenantFlowRuleMapper {

    private static final Logger log = LoggerFactory.getLogger(TenantFlowRuleMapper.class);

    /** 租户维度的聚合资源名——不是任何真实接口路径，专供"该租户的总 QPS"挂载 */
    public static final String OPEN_API_RESOURCE = "open_api";

    /**
     * **集群模式下**该租户用的资源名（第 9 步，2026-10-08）——把租户拼进资源名。
     *
     * <p><b>为什么不能沿用"一条规则 + limitApp=appId"</b>：源码 {@code FlowRuleChecker.canPassCheck}
     * 是这么分派的——
     * <pre>
     *   if (rule.isClusterMode()) return passClusterCheck(rule, ...);   // ← 直接走集群
     *   return passLocalCheck(rule, ...);                               // ← 本地才会做 origin 匹配
     * </pre>
     * 也就是说**集群模式下 {@code limitApp} 根本不会参与匹配**：服务器只认 flowId。
     * 后果实测（两个实例、20 发突发）：每个请求都会去问**库里每一个租户**的集群规则，
     * 撞上某个 count 很小的租户就直接被拒——20 发只过 1 个。
     *
     * <p>解法与 IP 维度同一个套路：**把租户放进资源名**，于是"这条规则只对该租户生效"
     * 由"谁 enter 了这个资源"决定（过滤器按 appId 进入），而不是由 limitApp 决定。
     * 代价：资源名基数 = 租户数（有界，且 6000 条处理链的上限远在天边）。
     *
     * <p>单机模式**不用**它：单机的 origin 匹配是正常的，沿用聚合资源 + limitApp=appId
     * 更省资源名（也避免既有用例与语义发生变化）。
     */
    public static String resourceFor(String appId, boolean clusterEnabled) {
        return clusterEnabled ? OPEN_API_RESOURCE + ":" + appId : OPEN_API_RESOURCE;
    }

    private TenantFlowRuleMapper() {
    }

    /**
     * 一个租户的配额输入（从 {@code open_app} 登记表 + {@code open_app_config} 覆盖解析后的结果）。
     *
     * @param appId 接入方标识（会成为规则的 {@code limitApp}，也是 Sentinel 的 origin）
     * @param qps   每秒上限；{@code <= 0} 表示不限（**不生成规则**）
     */
    public record TenantQuota(String appId, int qps) {
    }

    /**
     * 翻译结果。
     *
     * @param rules       可以直接灌给 {@code FlowRuleManager} 的规则列表
     * @param dropped     被预检丢弃的条数与其原因——**必须被上报**，否则就是静默失效
     */
    public record Mapping(List<FlowRule> rules, List<String> dropped) {
    }

    /**
     * 把租户配额翻译成规则。
     *
     * <p>纯函数：不碰数据库、不碰 Sentinel 的全局状态，方便单测。
     */
    public static Mapping map(List<TenantQuota> quotas, boolean clusterEnabled) {
        List<FlowRule> rules = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        for (TenantQuota quota : quotas) {
            if (quota.appId() == null || quota.appId().isBlank()) {
                dropped.add("appId 为空的配额项已跳过");
                continue;
            }
            if (quota.qps() <= 0) {
                // **0 = 不限 → 不发规则**（不是发 count=0，那在 Sentinel 里是"全拒"）
                continue;
            }

            FlowRule rule = new FlowRule();
            // 集群模式下用"每租户一个资源名"（origin 匹配在集群模式下不生效，见 resourceFor 的注释）；
            // 单机模式沿用聚合资源 + limitApp=appId
            boolean cluster = clusterEnabled;
            rule.setResource(resourceFor(quota.appId(), cluster));
            rule.setLimitApp(cluster ? "default" : quota.appId());
            rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            rule.setCount(quota.qps());
            // 快速失败：本项目的契约是"超限立即拒绝并告诉调用方重试"，
            // 而不是让它排队等着（排队会占住请求线程——那是 S2 阶段就裁决过的事）。
            rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);

            // 集群流控（第 9 步）：GLOBAL 阈值 = **所有实例合计**不超过 count。
            // 为什么必须配 flowId：客户端与服务端之间传的 token 请求里**只有 flowId**
            // （报文字段就三个：flowId/count/priority，没有资源名），服务端拿它查自己的规则表
            // ——两端必须对同一个 flowId 持有同一份规则。官方部署里 flowId 是控制台生成的，
            // 我们两端都从同一个库来，所以用同一个纯函数算（见 ClusterFlowId）。
            if (cluster) {
                rule.setClusterMode(true);
                rule.setClusterConfig(new ClusterFlowConfig()
                        .setFlowId(ClusterFlowId.of(rule.getResource(), rule.getLimitApp()))
                        // GLOBAL：集群总量 = count；默认的 AVG_LOCAL 是"每实例均值 × 实例数"，另一个语义
                        .setThresholdType(ClusterRuleConstant.FLOW_THRESHOLD_GLOBAL)
                        // 拿不到令牌时回退本地（=每个实例各限一份）。**这是降级**，
                        // 必须可见——由 MetricsClusterTokenClient 计数。
                        .setFallbackToLocalWhenFail(true));
            }

            // **集群不读 controlBehavior**（源码：ClusterFlowChecker 只看 count/thresholdType/flowId）：
            // 给一条"带预热/排队"的规则打开集群，等于把那两项**静默丢掉**。
            // 所以这里显式拦下，而不是等着它在运行时消失。
            if (rule.isClusterMode() && rule.getControlBehavior() != RuleConstant.CONTROL_BEHAVIOR_DEFAULT) {
                dropped.add("集群模式不支持控制行为（预热/排队在集群下会被静默忽略）：appId="
                        + quota.appId() + " behavior=" + rule.getControlBehavior());
                continue;
            }

            // 预检：不过就上报，不让它静默消失
            if (!FlowRuleUtil.isValidRule(rule)) {
                dropped.add("规则未通过 Sentinel 预检，已丢弃：appId=" + quota.appId()
                        + " qps=" + quota.qps() + "（这条**不会生效**，需要排查）");
                continue;
            }
            rules.add(rule);
        }

        if (clusterEnabled) {
            // flowId 冲突的后果是"两个租户共用一份额度"，而且**两端都不会有任何报错**——
            // 所以在这里拦一次：条数与去重后的条数不一致就拒绝整批并大声报出来。
            long clusterRules = rules.stream().filter(FlowRule::isClusterMode).count();
            long distinctFlowIds = rules.stream().filter(FlowRule::isClusterMode)
                    .map(r -> r.getClusterConfig().getFlowId()).distinct().count();
            if (clusterRules != distinctFlowIds) {
                String conflict = "flowId 冲突：集群规则 " + clusterRules + " 条，去重后只剩 "
                        + distinctFlowIds + " 个——**这些租户会共用同一份额度**，已拒绝整批规则";
                log.error(conflict);
                dropped.add(conflict);
                return new Mapping(List.of(), dropped);
            }
        }

        if (!dropped.isEmpty()) {
            // 用 WARN 而不是 DEBUG：这类"配了但不生效"必须能被看见
            log.warn("租户配额翻译：{} 条规则被丢弃（它们不会生效）——{}", dropped.size(), dropped);
        }
        return new Mapping(rules, dropped);
    }
}
