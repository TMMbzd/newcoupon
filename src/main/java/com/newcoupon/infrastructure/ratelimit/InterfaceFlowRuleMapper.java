package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把"接口（平台级）配额"翻译成 Sentinel 的 {@link FlowRule}（迁移第 8 步，2026-10-08）。
 *
 * <p><b>为什么要迁</b>：接口维度原来是自己调 Bucket4j（{@code c:rl:res:{...}}），
 * 而**预热与匀速排队只能在 Sentinel 的规则上配**（那是 {@code controlBehavior} 的两种取值）。
 * 不迁，这两项新增能力就没有落脚点。
 *
 * <p><b>顺带修掉一个既有缺陷</b>：原来的键是 {@code c:rl:res:} + 去掉 {@code /open/v1/} 前缀的
 * **实际路径**——于是 {@code GET /open/v1/templates/42} 的键是 {@code templates/42}，
 * **每个 id 一个桶**：平台上限被稀释成"每 id 一份"，而且 Redis 键无界。
 * 迁成"按**路径模式**一条规则"之后，{@code /open/v1/templates/{id}} 共用一个桶，键也就有界了。
 *
 * <p><b>为什么资源名用"匹配到的路径模式"而不是 {@code @OpenResource} 的契约名</b>：
 * 适配器（{@code SentinelWebInterceptor}）enter 的资源名**就是**路径模式
 * （它读的是 {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}）。
 * 让规则的资源名与它**同源**，就永远不会出现"规则配了但不命中"——
 * 而 Sentinel 对不命中的规则是**完全静默**的（第 0 步被咬过一次：
 * 资源名按直觉写成 {@code sentinel-probe}，规则灌进去纹丝不动）。
 * 另一条路（教适配器用契约名）要多一个"名字来源"，一旦漂移就是静默失效，不值。
 *
 * <p>与 {@link TenantFlowRuleMapper} 一样是**纯函数**：不碰 Sentinel 全局状态，方便单测。
 */
public final class InterfaceFlowRuleMapper {

    private static final Logger log = LoggerFactory.getLogger(InterfaceFlowRuleMapper.class);

    private InterfaceFlowRuleMapper() {
    }

    /**
     * 由"预热时长 / 排队上限"决定控制行为。
     *
     * <p>四种取值：0=快速失败、1=预热、2=匀速排队、**3=预热+排队**（1.8.10 才有的合成控制器）。
     * 两个都为 0 时**显式**写 0（而不是依赖默认值）——配置项与行为必须一一对应，看代码就能对上配置。
     */
    private static int controlBehaviorOf(int warmupSeconds, int queueMs) {
        if (warmupSeconds > 0 && queueMs > 0) {
            return RuleConstant.CONTROL_BEHAVIOR_WARM_UP_RATE_LIMITER;
        }
        if (warmupSeconds > 0) {
            return RuleConstant.CONTROL_BEHAVIOR_WARM_UP;
        }
        if (queueMs > 0) {
            return RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER;
        }
        return RuleConstant.CONTROL_BEHAVIOR_DEFAULT;
    }

    /**
     * 翻译结果（与 {@link TenantFlowRuleMapper.Mapping} 结构相同，便于调用方合并后统一上报）。
     *
     * @param rules   可以直接灌给 {@code FlowRuleManager} 的规则
     * @param dropped 被预检丢弃的条数与其原因——**必须上报**，否则"配了但不生效"没人知道
     */
    public record Mapping(List<FlowRule> rules, List<String> dropped) {
    }

    /**
     * @param patterns      启动期扫出来的开放面路径模式（如 {@code /open/v1/coupons}）
     * @param enabled       全站配置 {@code coupon.ratelimit.resource.enabled}（默认关）
     * @param qps           全站配置 {@code coupon.ratelimit.resource.qps}；{@code <=0} 表示不限
     * @param warmupSeconds 冷启动预热时长（第 8 步新增）；{@code <=0} = 不预热
     * @param queueMs       匀速排队的**最大等待**（第 8 步新增）；{@code <=0} = 不排队（默认）
     */
    public static Mapping map(Collection<String> patterns, boolean enabled, int qps,
                              int warmupSeconds, int queueMs) {
        List<FlowRule> rules = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        if (!enabled || qps <= 0) {
            // **0 = 不限 → 不发规则**（不是发一条 count=0 的：在 Sentinel 里那是"全部拒绝"，
            // 方向正好相反——这是本迁移最容易搞错的一处，见 TenantFlowRuleMapper 的注释）
            return new Mapping(rules, dropped);
        }
        if (patterns == null || patterns.isEmpty()) {
            // 走到这里说明"开关开了但一个模式都没扫到"——那是接线错误，必须响
            dropped.add("接口维度已启用，但路径模式目录为空：规则一条都不会生成（接口维度形同不存在）");
            log.error("接口维度已启用，但启动期没有扫到任何开放面路径模式——"
                    + "接口维度不会生效，请检查 catalog 的扫描时机与 /open/** 的映射");
            return new Mapping(rules, dropped);
        }

        for (String pattern : patterns) {
            FlowRule rule = new FlowRule();
            rule.setResource(pattern);
            // limitApp="default"：**不区分来源**——接口维度的语义是"这个接口被全平台打了多少"，
            // 与是哪个租户打的无关（租户维度才是按 appId 分开的）
            rule.setLimitApp("default");
            rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            rule.setCount(qps);
            // 控制行为（第 8 步）：四种取值里 Sentinel 1.8.10 都支持，
            // 其中 **3 = 预热 + 排队一起**（WarmUpRateLimiterController）——不需要拼两条规则
            // （两条规则是 AND，各自限各自的桶，拼不出"一个桶同时具备两种能力"）。
            //
            // 注意两条硬约束（否则规则会被 Sentinel **静默丢弃**，我们的预检会把它报成 dropped）：
            //   · behavior=1/3 要求 warmUpPeriodSec > 0；
            //   · behavior=2/3 要求 maxQueueingTimeMs > 0。
            int behavior = controlBehaviorOf(warmupSeconds, queueMs);
            rule.setControlBehavior(behavior);
            if (warmupSeconds > 0) {
                rule.setWarmUpPeriodSec(warmupSeconds);
            }
            if (queueMs > 0) {
                // **排队 = sleep 调用线程**（1.8.10 是 ThrottlingController），所以这个值
                // 直接决定"最多有多少个 Tomcat 线程被占着等"：等待线程数上界 ≈ qps × queueMs / 1000。
                // 默认 0（不排队）是刻意的：S2 当初否决排队就是这个理由
                // （同步模型下排队会把"拒绝一部分"变成"占住线程"），第 8 步只是把它变成
                // 一个**有界、默认关**的开关。开启前请先算两道预算，见 docs/S3 §10.7。
                rule.setMaxQueueingTimeMs(queueMs);
            }

            if (!FlowRuleUtil.isValidRule(rule)) {
                dropped.add("接口维度规则未通过 Sentinel 预检，已丢弃：resource=" + pattern
                        + " qps=" + qps + "（这条**不会生效**）");
                continue;
            }
            rules.add(rule);
        }
        return new Mapping(rules, dropped);
    }
}
