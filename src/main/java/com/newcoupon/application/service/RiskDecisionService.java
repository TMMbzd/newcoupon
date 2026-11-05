package com.newcoupon.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.infrastructure.id.SnowflakeIdGenerator;
import com.newcoupon.infrastructure.metrics.RiskMetrics;
import com.newcoupon.infrastructure.persistence.mapper.RiskDecisionLogMapper;
import com.newcoupon.infrastructure.persistence.po.RiskDecisionLogPO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 风控决策服务（V6 4A 最小版）——决策框架的唯一入口。
 *
 * <p><b>总闸（SwitchKey.RISK_DECISION，默认关）</b>：关 = 直接 PASS **且不写决策日志**
 * ——回归零变化承诺：关时热路径零额外 IO（不碰 Redis/DB，与 1C 预算回滚承诺同款纪律，
 * 用例钉住）。开 = 评估全部规则 + 同步写决策日志。租户灰度走 SwitchService 三层取值
 * （租户 open_app_config.switch.risk.decision.enabled → 全站 coupon.switch.* → 默认）。
 *
 * <p><b>决策合成</b>：按注入顺序（@Order）评估支持的规则；多规则命中取最严
 * （REJECT &gt; REVIEW &gt; CHALLENGE &gt; PASS）；白名单的 PASS 与黑名单的 REJECT
 * 都终止评估（短路省掉后续规则的 IO）。规则内部故障不外泄：兜住、标 degraded、
 * 继续评估——风控自身故障不允许打死业务（fail-open）。
 *
 * <p><b>决策日志为什么同步写、写失败又不挡业务</b>（与审计"可丢"语义的差别）：
 * 日志是"可回放"的根基——异步队列丢一行，那次决策就永远无法复盘，回放口径从
 * "全量可解释"静默退化成"部分可解释"，而且没人知道缺了哪部分。所以同步写。
 * 但写失败（存储抖动）不能让领券挂掉：catch + ERROR + coupon_risk_decision_log_write_fail_total
 * 指标。两个语义合起来：**尽力全量留痕，绝不为留痕牺牲业务**；指标涨=这段时间
 * 的决策不可回放，要追存储。
 *
 * <p><b>degraded 语义</b>：因子不完整仍出决策（fail-open 且可见）。两类已知原因：
 * 设备头缺失（透传维度没接）、设备计数 Redis 不可用。factors_json 里永远能找到
 * "哪条规则为什么没说话"。
 *
 * <p><b>REJECT 的处置不在这里</b>：decide 只返回结果。领券=接线点抛 7003；
 * 退款（刀 2）=照做+打标（合规红线）——处置策略属于场景，不属于框架。
 */
@Service
public class RiskDecisionService {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionService.class);

    /** matched_rules 列宽（V48）；规则多了截断并 ERROR——宁可少记也不撑爆列 */
    private static final int MATCHED_RULES_MAX = 255;

    private final SwitchService switchService;
    private final List<RiskRule> rules;   // Spring 按 @Order 注入=固定评估顺序
    private final RiskDecisionLogMapper logMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final RiskMetrics metrics;
    private final ObjectMapper objectMapper;

    public RiskDecisionService(SwitchService switchService,
                               List<RiskRule> rules,
                               RiskDecisionLogMapper logMapper,
                               SnowflakeIdGenerator idGenerator,
                               RiskMetrics metrics,
                               ObjectMapper objectMapper) {
        this.switchService = switchService;
        this.rules = rules;
        this.logMapper = logMapper;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * 决策结果（matchedRules=产出动作的规则；关闸时恒为 PASS/空/false）。
     * decisionId=决策日志行的主键（4B）：退款场景的结算冻结 holdNo=RISK-{decisionId}
     * 以它把 hold 行与决策行缝在一起（关闸时无日志行，decisionId=null）。
     * logWritten（审查 m3）=决策日志**确实落库**——写失败时 false：decisionId 可能指向
     * 不存在的行，下游（退款 hold）不得拿它当可靠取证锚，要换 traceId 兜底。
     */
    public record RiskDecision(RiskAction action, List<String> matchedRules, boolean degraded,
                               Long decisionId, boolean logWritten) {

        static RiskDecision off() {
            return new RiskDecision(RiskAction.PASS, List.of(), false, null, false);
        }
    }

    /**
     * 一次决策。开关关 → 直接 PASS 不写日志（零额外 IO）；开 → 评估 + 同步落日志。
     * 本方法不抛业务异常——REJECT 的处置由场景接线点决定（见类注释）。
     */
    public RiskDecision decide(RiskContext ctx) {
        if (!switchService.isOn(SwitchKey.RISK_DECISION, ctx.appId())) {
            return RiskDecision.off();
        }
        long decisionId = idGenerator.next();   // 先取 id 再评估：决策与日志行号一对一
        RiskFactors factors = new RiskFactors();
        List<String> matched = new ArrayList<>();
        RiskAction action = evaluate(ctx, factors, matched);

        boolean logWritten = writeLog(decisionId, ctx, factors, matched, action);

        metrics.recordDecision(action.name());
        return new RiskDecision(action, List.copyOf(matched), factors.degraded(),
                decisionId, logWritten);
    }

    /**
     * 风控决策是否启用（同一三层取值，decide 内部会再判一次——租户层有缓存，二次读无负担）。
     * 给接线点的**前置分支**用：只在开时才做"幂等重放豁免"等额外查询，
     * 保证关时热路径零额外 IO 的承诺不被前置查询破坏（审查 C2 的配套）。
     */
    public boolean enabled(String appId) {
        return switchService.isOn(SwitchKey.RISK_DECISION, appId);
    }

    /**
     * 规则评估公共内核（decide 与 replay 共用，保证两条路的判定逻辑只有一份）。
     * matched 由调用方收集（回放也要用它做 diff）。
     */
    private RiskAction evaluate(RiskContext ctx, RiskFactors factors, List<String> matched) {
        RiskAction action = RiskAction.PASS;
        for (RiskRule rule : rules) {
            if (!rule.supports(ctx.scene())) {
                continue;
            }
            Optional<RiskAction> hit;
            try {
                hit = rule.evaluate(ctx, factors);
            } catch (RuntimeException e) {
                // 规则自己没兜住的最坏情况：标 degraded 继续评估（fail-open 的最后防线）
                factors.put(rule.ruleId() + ".error", e.toString());
                factors.markDegraded();
                log.error("风控规则评估异常（跳过该规则，决策继续）：rule={} ctx.appId={}",
                        rule.ruleId(), ctx.appId(), e);
                continue;
            }
            if (hit.isPresent()) {
                matched.add(rule.ruleId());
                if (hit.get() == RiskAction.PASS) {
                    return RiskAction.PASS;   // 白名单短路：明确放行盖过一切
                }
                if (hit.get().moreSevereThan(action)) {
                    action = hit.get();       // 多规则命中取最严
                }
                if (action == RiskAction.REJECT) {
                    return RiskAction.REJECT; // 拒绝即终止：省掉后续规则的 IO
                }
            }
        }
        return action;
    }

    /**
     * 同步写决策日志；失败不挡业务（ERROR + 指标，见类注释的语义裁定）。
     * 返回是否落库成功（审查 m3）：decisionId 是"预铸"的——写失败时它指向不存在的行，
     * 调用方（退款 hold 的 holdNo）需要知道这个事实并换 traceId 兜底。
     */
    private boolean writeLog(long decisionId, RiskContext ctx, RiskFactors factors,
                             List<String> matched, RiskAction action) {
        try {
            RiskDecisionLogPO po = new RiskDecisionLogPO();
            po.setId(decisionId);
            po.setScene(ctx.scene().name());
            po.setAppId(ctx.appId());
            po.setUserId(ctx.userId());
            po.setDeviceId(ctx.deviceId());
            po.setIp(ctx.ip());
            po.setFactorsJson(objectMapper.writeValueAsString(factors.snapshot()));
            String joined = String.join(",", matched);
            if (joined.length() > MATCHED_RULES_MAX) {
                log.error("matched_rules 超过列宽 {}，截断：rules={}", MATCHED_RULES_MAX, joined);
                joined = joined.substring(0, MATCHED_RULES_MAX);
            }
            po.setMatchedRules(joined.isEmpty() ? null : joined);
            po.setAction(action.name());
            po.setDegraded(factors.degraded() ? 1 : 0);
            po.setTraceId(MDC.get(com.newcoupon.infrastructure.web.TraceIdFilter.TRACE_ID));
            po.setRequestId(ctx.requestId());
            po.setCreatedAt(LocalDateTime.now().withNano(0));
            logMapper.insert(po);
            return true;
        } catch (Exception e) {
            log.error("决策日志写入失败（不挡业务，但这段时间的决策不可回放）：scene={} appId={} userId={}",
                    ctx.scene(), ctx.appId(), ctx.userId(), e);
            metrics.recordDecisionLogWriteFail();
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 回放（查询服务编排，判定逻辑回到这里——两份实现迟早漂移）
    // ------------------------------------------------------------------

    /** 回放评估结果：只重算无副作用的规则；有副作用的如实列出跳过原因 */
    public record ReplayEvaluation(RiskAction action, List<String> matchedRules,
                                   List<String> replayedRuleIds,
                                   List<SkippedRule> skippedRules) {
    }

    /** 被跳过的回放规则（ruleId → 原因；"没算"比"算了个错的"诚实） */
    public record SkippedRule(String ruleId, String reason) {
    }

    /**
     * 只读回放的评估内核：与 {@link #decide} 同一套"取最严"合成，但每条规则走
     * {@link RiskRule#replay}（默认=线上同路径；副作用规则覆盖为"日志因子×当前配置"的
     * 纯重算），不可回放的列入 skippedRules。**不写决策日志、不看开关**——回放是
     * 离线分析（对照当前规则集与当时快照），开关关着也应能复盘历史。
     * 与 decide 的另一差别：不短路（白名单/REJECT 仍评估完全部可回放规则）——
     * 回放的价值在完整对照，省一次只读查询没有意义。
     *
     * @param loggedFactors 该行日志的 factors_json 反序列化快照——阈值重算类 replay 的输入
     */
    public ReplayEvaluation replayEvaluate(RiskContext ctx, Map<String, Object> loggedFactors) {
        Map<String, Object> logged = loggedFactors == null ? Map.of() : loggedFactors;
        RiskFactors factors = new RiskFactors();
        List<String> matched = new ArrayList<>();
        List<String> replayed = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();
        RiskAction action = RiskAction.PASS;
        for (RiskRule rule : rules) {
            if (!rule.supports(ctx.scene())) {
                continue;
            }
            if (!rule.replayable()) {
                skipped.add(new SkippedRule(rule.ruleId(), "有副作用且日志因子不足以重算，回放不评估"));
                continue;
            }
            replayed.add(rule.ruleId());
            try {
                Optional<RiskAction> hit = rule.replay(ctx, logged, factors);
                if (hit.isPresent()) {
                    matched.add(rule.ruleId());
                    if (hit.get().moreSevereThan(action)) {
                        action = hit.get();
                    }
                }
            } catch (RuntimeException e) {
                skipped.add(new SkippedRule(rule.ruleId(), "回放时评估失败: " + e));
            }
        }
        return new ReplayEvaluation(action, List.copyOf(matched),
                List.copyOf(replayed), List.copyOf(skipped));
    }
}
