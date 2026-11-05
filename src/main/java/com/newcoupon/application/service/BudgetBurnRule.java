package com.newcoupon.application.service;

import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskScene;
import com.newcoupon.infrastructure.metrics.RiskMetrics;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetAccountMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetLedgerMapper;
import com.newcoupon.infrastructure.persistence.po.CouponActivityPO;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetAccountPO;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 预算燃烧信号规则（ruleId=R_BUDGET_BURN，V6 4B）：活动预算账户**当日账本流水行数**
 * 超阈 → REVIEW（放行+留痕+指标）。
 *
 * <p><b>挡的攻击形态</b>：脚本/批量工具把某活动的预算以异常速度烧掉（领券风暴的
 * “账本视角”）。预算闸门本身已挡超额（1C），这条规则回答的是**速度**——“今天这池子
 * 被戳了 5000 次”即使每次都合法也是可疑加速。所以动作是 REVIEW 不是 REJECT：
 * 预算信号是“可疑”不是“确定攻击”，REJECT 会把合法大促全员打死；REVIEW 让风控
 * 看到盘面（决策日志 + coupon_risk_decision_total{action=REVIEW}）再人工介入
 * （限领/停售/加名单都是既有手段）。
 *
 * <p><b>口径（最省实现，一条 SQL）</b>：coupon_budget_ledger 当日（create_time ≥ 今日
 * 00:00）该活动账户的流水**行数**（RESERVE/CONFIRM/RELEASE/EXPIRE 全算），
 * &gt; coupon.risk.budget-burn.daily-ledger-threshold（默认 1000 行/日）→ REVIEW。
 * 为什么数行数不是 SUM 金额：金额口径被单张面额带偏（一张 100 元券与一百张 1 元券
 * 金额相同、速度完全不同）；行数就是“账本被戳的次数”，与速度同构。
 * 为什么不做“对比昨夜快照”：没有快照表，为一条 REVIEW 规则建日任务不值——
 * 阈值本身就是“正常日流水量级”的人工标定，登记 runbook 升级路径。
 *
 * <p><b>跳过与降级</b>（fail-open，与设备日限同纪律）：
 * <ul>
 *   <li>上下文没带 templateId / 模板不存在 / 活动未挂或未开预算（budget_enabled=0）
 *       → 因子缺失 degraded 跳过——预算闸门都没开，没有“加速”可言；
 *       degraded 指标 reason=budget-not-enabled（盘面上能看到“哪些决策这条规则没说话”）；</li>
 *   <li>账户行不存在 → **不是** degraded：账户懒建于首次预占（V42），开预算但零发放的
 *       活动没有账户=当日流水 0，正常放行（因子里记 ledgerToday=0）；</li>
 *   <li>计数查询故障 → 跳过 + degraded + 指标（reason=budget-unavailable）——
 *       依赖故障在规则内兜住（RiskRule 契约），不外泄成业务失败。</li>
 * </ul>
 *
 * <p><b>可回放</b>（replayable=true，照设备日限 B 簇的 replay 模式）：ledger 行数是
 * 时点数据，回放不能拿“今天的行数”冒充“当时的行数”——日志存快照值
 * （budget.ledgerToday=当日当时行数），replay 用**日志快照 × 当前阈值**纯内存重算：
 * 调小阈值回放历史行即可量化“若阈值是 X，多早能亮 REVIEW”。
 */
@Component
@Order(300)   // 评估序第三：名单（100）→ 设备日限（200）→ 预算燃烧（信号类规则殿后）
public class BudgetBurnRule implements RiskRule {

    static final String REASON_BUDGET_NOT_ENABLED = "budget-not-enabled";
    static final String REASON_BUDGET_UNAVAILABLE = "budget-unavailable";

    private final CachedTemplateReader templateReader;
    private final CachedActivityReader activityReader;
    private final CouponBudgetAccountMapper accountMapper;
    private final CouponBudgetLedgerMapper ledgerMapper;
    private final RiskMetrics riskMetrics;
    private final int dailyLedgerThreshold;

    public BudgetBurnRule(CachedTemplateReader templateReader,
                          CachedActivityReader activityReader,
                          CouponBudgetAccountMapper accountMapper,
                          CouponBudgetLedgerMapper ledgerMapper,
                          RiskMetrics riskMetrics,
                          @Value("${coupon.risk.budget-burn.daily-ledger-threshold:1000}")
                          int dailyLedgerThreshold) {
        this.templateReader = templateReader;
        this.activityReader = activityReader;
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.riskMetrics = riskMetrics;
        this.dailyLedgerThreshold = dailyLedgerThreshold;
    }

    @Override
    public String ruleId() {
        return "R_BUDGET_BURN";
    }

    @Override
    public boolean supports(RiskScene scene) {
        // 只对领券有意义：预算账户的流水由“发券”驱动，锁券/核销/退款不动预算账本
        return scene == RiskScene.RECEIVE;
    }

    @Override
    public boolean replayable() {
        // 回放走 replay() 的“日志快照 × 当前阈值”纯重算（不查库）——见类注释
        return true;
    }

    @Override
    public Optional<RiskAction> evaluate(RiskContext ctx, RiskFactors factors) {
        factors.put("budget.dailyLedgerThreshold", dailyLedgerThreshold);
        if (ctx.templateId() == null) {
            // 交易场景（无模板语义）或老接线点没带锚：这条规则没话说，如实记因子
            factors.put("budget.skipped", REASON_BUDGET_NOT_ENABLED);
            factors.markDegraded();
            riskMetrics.recordDecisionDegraded(REASON_BUDGET_NOT_ENABLED);
            return Optional.empty();
        }
        var template = templateReader.get(ctx.templateId()).orElse(null);
        if (template == null || template.getActivityId() <= 0) {
            factors.put("budget.skipped", REASON_BUDGET_NOT_ENABLED);
            factors.markDegraded();
            riskMetrics.recordDecisionDegraded(REASON_BUDGET_NOT_ENABLED);
            return Optional.empty();
        }
        long activityId = template.getActivityId();
        factors.put("budget.activityId", activityId);
        CouponActivityPO activity = activityReader.get(activityId).orElse(null);
        if (activity == null || activity.getBudgetEnabled() == null
                || activity.getBudgetEnabled() != CouponActivityPO.BUDGET_ENABLED) {
            // 活动没开预算闸门：预占/流水都不存在，“燃烧速度”无定义——跳过并可见
            factors.put("budget.skipped", REASON_BUDGET_NOT_ENABLED);
            factors.markDegraded();
            riskMetrics.recordDecisionDegraded(REASON_BUDGET_NOT_ENABLED);
            return Optional.empty();
        }
        CouponBudgetAccountPO account = accountMapper.selectByScope(
                CouponBudgetAccountPO.SCOPE_ACTIVITY, activityId,
                CouponBudgetAccountPO.PERIOD_TOTAL, CouponBudgetAccountPO.PERIOD_KEY_TOTAL);
        if (account == null) {
            // 开预算但从未预占（账户懒建）：当日流水 0，正常放行——不是 degraded
            factors.put("budget.accountId", 0L);
            factors.put("budget.ledgerToday", 0L);
            return Optional.empty();
        }
        factors.put("budget.accountId", account.getId());
        long todayRows;
        try {
            todayRows = ledgerMapper.countCreatedSince(
                    account.getId(), LocalDate.now().atStartOfDay());
        } catch (RuntimeException e) {
            // 因子源不可用：fail-open（放行）+ degraded + 可见——与设备日限同款三件套
            factors.put("budget.skipped", REASON_BUDGET_UNAVAILABLE);
            factors.markDegraded();
            riskMetrics.recordDecisionDegraded(REASON_BUDGET_UNAVAILABLE);
            return Optional.empty();
        }
        factors.put("budget.ledgerToday", todayRows);
        return todayRows > dailyLedgerThreshold ? Optional.of(RiskAction.REVIEW) : Optional.empty();
    }

    /**
     * 回放（照设备日限 B 簇模式）：**日志快照值 × 当前阈值**的纯内存重算——
     * ledger 行数是时点数据，回放不能查“现在的行数”（那是另一次观察）。
     * 当时跳过的行（budget.skipped 在案）→ 如实说算不了，返回 empty。
     */
    @Override
    public Optional<RiskAction> replay(RiskContext ctx, Map<String, Object> loggedFactors,
                                       RiskFactors out) {
        Object logged = loggedFactors.get("budget.ledgerToday");
        if (!(logged instanceof Number number)) {
            out.put("budget.replay", "no-logged-count");
            return Optional.empty();
        }
        out.put("budget.replay", "threshold-recompute");
        out.put("budget.ledgerToday", number.longValue());
        out.put("budget.dailyLedgerThreshold", dailyLedgerThreshold);
        return number.longValue() > dailyLedgerThreshold
                ? Optional.of(RiskAction.REVIEW) : Optional.empty();
    }
}
