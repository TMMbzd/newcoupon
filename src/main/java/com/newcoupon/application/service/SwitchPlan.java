package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import java.util.List;

/**
 * 三级预案（S2 第 10 步）——三份**预置的配置目标状态**清单。
 *
 * <p><b>预案的价值在"出事时人不用临场想"</b>：把"该关哪些、收到多紧"提前写好、一键套用。
 * 临场决策的正确率与速度都不可靠，而预案是冷静时定的。
 *
 * <p><b>为什么是"配置"而不只是"开关"</b>：设计文档 §4.4 最初把预案定义为"一组开关"，
 * 但蓝（预防）级的意图是"收一收，不影响体验"——这在本项目的开关清单里<b>找不到对应项</b>
 * （8 个开关都是"关了就少个功能"，没有"预防性收一收"这种）。真正的预防手段是
 * <b>调限流参数</b>（打开冷启动预热、把每秒上限收紧）。所以预案项是
 * {@code (配置键, 目标值)}，键可以来自 {@link SwitchKey} 也可以来自 {@link TenantConfigKey}。
 *
 * <p><b>全量物化，不是增量</b>：三份预案声明<b>完全相同的一组键</b>，每个键都给出确定的目标值。
 * 否则会留残影——举个真实的例子：如果只有红预案设了 {@code rate_limit.qps}，
 * 那么"先红后黄"会把红的限流值留在那里（黄没提它），于是
 * <b>"从红套黄"与"直接套黄"结果不同</b>。这种顺序敏感性在止血时是灾难：
 * 你以为套了黄，实际带着红的限制在跑。
 *
 * <p>类初始化时有两条断言强制这个约束（漏了直接启动失败，比"用一条用例去守"更硬）：
 * ① 每份预案都覆盖全部<b>已接线</b>的开关；② 三份预案的键集合完全相同。
 *
 * <p><b>代价与兜底</b>：全量物化会覆盖运维此前手工配过的值（比如 qps 原本配了 200）。
 * 这是有意的取舍——止血时"可预测"比"保留细节"重要；而<b>快照补偿回滚</b>保证了
 * 恢复时能把这些值原样还回去。
 *
 * <p><b>不做自动升降级</b>：自动判据容易误判，本阶段先做"人按一下"。
 */
public enum SwitchPlan {

    /**
     * 蓝（预防）：不关任何功能，只把限流收一收。
     *
     * <p>限流值给的是**保守占位**——本项目没有真实的容量基线，实际部署时要按压测结果校准
     * （登记在 L54"限流阈值按压测配平"）。标成占位而不是假装精确，是为了不让人误以为它有意义。
     */
    BLUE("蓝", "预防：收一收限流，不关任何开关（用户体验不受影响）",
            new PlanEntry(SwitchKey.RECEIVE.configKey(), "true"),
            new PlanEntry(SwitchKey.TRADE_LOCK.configKey(), "true"),
            new PlanEntry(SwitchKey.TRADE_CONFIRM.configKey(), "true"),
            new PlanEntry(SwitchKey.TRADE_RELEASE.configKey(), "true"),
            new PlanEntry(SwitchKey.TEMPLATE_PUBLISH.configKey(), "true"),
            new PlanEntry(SwitchKey.OUTBOX_DISPATCH.configKey(), "true"),
            new PlanEntry(TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS.configKey(), "60"),
            new PlanEntry(TenantConfigKey.RATE_LIMIT_QPS.configKey(), "150")),

    /**
     * 黄（局部降级）：关掉非核心，保住发券与用券。
     *
     * <p>关的两个：<b>模板发布</b>（建券是运营动作，可以等）与<b>发件箱投递</b>
     * （消息不丢，恢复后补投——这是发件箱模式的红利，敢于关它是因为它真的不会丢）。
     *
     * <p>限流回到"不限"（{@code qps=0} 是本项目"不设限"的约定值）：黄的手段是关功能，
     * 不是收流量——但它仍要**显式声明**这一项，否则红预案设过的值会残留下来。
     */
    YELLOW("黄", "局部降级：关掉非核心（模板发布、发件箱投递）、限流回到不干预，核心链路全保",
            new PlanEntry(SwitchKey.RECEIVE.configKey(), "true"),
            new PlanEntry(SwitchKey.TRADE_LOCK.configKey(), "true"),
            new PlanEntry(SwitchKey.TRADE_CONFIRM.configKey(), "true"),
            new PlanEntry(SwitchKey.TRADE_RELEASE.configKey(), "true"),
            new PlanEntry(SwitchKey.TEMPLATE_PUBLISH.configKey(), "false"),
            new PlanEntry(SwitchKey.OUTBOX_DISPATCH.configKey(), "false"),
            new PlanEntry(TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS.configKey(), "0"),
            new PlanEntry(TenantConfigKey.RATE_LIMIT_QPS.configKey(), "0")),

    /**
     * 红（保命）：只保留发券与用券，并进一步收紧限流。
     *
     * <p>与黄的区别在**限流收得更紧**（开关状态两者相同——现有 8 个开关里，
     * 非核心的只有模板发布与发件箱投递两个，黄已经全关了）。
     * 宁可有请求被拒也不让系统整体垮掉：被拒的请求是可重试的 429，垮掉是全都没得用。
     */
    RED("红", "保命：只保留发券与用券，并进一步收紧限流",
            new PlanEntry(SwitchKey.RECEIVE.configKey(), "true"),
            new PlanEntry(SwitchKey.TRADE_LOCK.configKey(), "true"),
            new PlanEntry(SwitchKey.TRADE_CONFIRM.configKey(), "true"),
            new PlanEntry(SwitchKey.TRADE_RELEASE.configKey(), "true"),
            new PlanEntry(SwitchKey.TEMPLATE_PUBLISH.configKey(), "false"),
            new PlanEntry(SwitchKey.OUTBOX_DISPATCH.configKey(), "false"),
            new PlanEntry(TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS.configKey(), "0"),
            new PlanEntry(TenantConfigKey.RATE_LIMIT_QPS.configKey(), "20"));

    static {
        // 两条全量物化断言（见类注释）。**放在类初始化里而不是用例里**：
        // 用例可能被人跳过、改名或删掉，类初始化不会——配错了应用直接起不来。
        for (SwitchPlan plan : values()) {
            // ① 每份预案必须覆盖全部已接线的开关（未接线的不该进预案：套了也没效果）
            for (SwitchKey key : SwitchKey.values()) {
                if (!key.wired()) {
                    continue;
                }
                boolean covered = plan.entries.stream()
                        .anyMatch(e -> e.configKey().equals(key.configKey()));
                if (!covered) {
                    throw new IllegalStateException("预案 " + plan.name() + " 未覆盖已接线的开关 "
                            + key.configKey() + "——每份预案都必须对全部已接线开关显式声明目标值");
                }
            }
        }
        // ② 三份预案的键集合必须**完全相同**——否则会留残影：
        //    "先红后黄"会把红的限流值留在那里（黄没提它），于是
        //    "从红套黄"与"直接套黄"结果不同。止血时这种顺序敏感是灾难。
        java.util.Set<String> baseline = keySetOf(BLUE);
        for (SwitchPlan plan : values()) {
            java.util.Set<String> keys = keySetOf(plan);
            if (!keys.equals(baseline)) {
                java.util.Set<String> missing = new java.util.LinkedHashSet<>(baseline);
                missing.removeAll(keys);
                java.util.Set<String> extra = new java.util.LinkedHashSet<>(keys);
                extra.removeAll(baseline);
                throw new IllegalStateException("预案 " + plan.name() + " 的键集合与 "
                        + BLUE.name() + " 不一致——缺少 " + missing + "，多出 " + extra
                        + "。三份预案必须声明同一组键，否则切换预案会留残影");
            }
        }
    }

    private static java.util.Set<String> keySetOf(SwitchPlan plan) {
        return plan.entries.stream().map(PlanEntry::configKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** 一条预案项：某个配置键的目标值 */
    public record PlanEntry(String configKey, String targetValue) {
    }

    private final String label;
    private final String intent;
    private final List<PlanEntry> entries;

    SwitchPlan(String label, String intent, PlanEntry... entries) {
        this.label = label;
        this.intent = intent;
        this.entries = List.of(entries);
    }

    public String label() {
        return label;
    }

    /** 人话意图——管理面返回给运维，让"这份预案是干什么的"不需要翻代码 */
    public String intent() {
        return intent;
    }

    public List<PlanEntry> entries() {
        return entries;
    }

    /** 按级别名反查（管理面路径参数用的是 {@code BLUE}/{@code YELLOW}/{@code RED}） */
    public static SwitchPlan fromLevel(String level) {
        if (level != null) {
            for (SwitchPlan p : values()) {
                if (p.name().equalsIgnoreCase(level.trim())) {
                    return p;
                }
            }
        }
        throw new BizException(ErrorCode.PARAM_ERROR,
                "未知的预案级别：" + level + "（可选：" + java.util.Arrays.toString(values()) + "）");
    }
}
