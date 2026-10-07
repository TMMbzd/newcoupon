package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
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
    public static Mapping map(List<TenantQuota> quotas) {
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
            rule.setResource(OPEN_API_RESOURCE);
            rule.setLimitApp(quota.appId());
            rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            rule.setCount(quota.qps());
            // 快速失败：本项目的契约是"超限立即拒绝并告诉调用方重试"，
            // 而不是让它排队等着（排队会占住请求线程——那是 S2 阶段就裁决过的事）。
            rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);

            // 预检：不过就上报，不让它静默消失
            if (!FlowRuleUtil.isValidRule(rule)) {
                dropped.add("规则未通过 Sentinel 预检，已丢弃：appId=" + quota.appId()
                        + " qps=" + quota.qps() + "（这条**不会生效**，需要排查）");
                continue;
            }
            rules.add(rule);
        }

        if (!dropped.isEmpty()) {
            // 用 WARN 而不是 DEBUG：这类"配了但不生效"必须能被看见
            log.warn("租户配额翻译：{} 条规则被丢弃（它们不会生效）——{}", dropped.size(), dropped);
        }
        return new Mapping(rules, dropped);
    }
}
