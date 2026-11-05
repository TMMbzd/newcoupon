package com.newcoupon.application.service;

import com.newcoupon.domain.risk.RiskAction;
import com.newcoupon.domain.risk.RiskScene;
import java.util.Map;
import java.util.Optional;

/**
 * 风控规则接口（V6 4A 最小版）——**代码规则 + 配置阈值**，不做规则表。
 *
 * <p><b>一条规则=一个类</b>，契约四件：
 * <ol>
 *   <li>{@link #ruleId()}：稳定标识，进决策日志的 matched_rules 与回放 diff——
 *       改名=历史日志无法对照，一经发布不可改；</li>
 *   <li>{@link #supports}：场景声明（设备日限只对领券有意义）；</li>
 *   <li>{@link #evaluate}：读上下文、写因子、返回动作。返回 empty=未命中（继续评估）；
 *       返回 PASS=白名单短路（终止评估）；返回 REJECT=终止评估；REVIEW/CHALLENGE
 *       记录命中但让后续规则继续（最终动作取最严）；</li>
 *   <li>{@link #replayable()}：能否参与回放。有副作用的规则（设备日限的 INCR）
 *       必须返回 false——回放是只读的，不能因为复盘给用户多计一次数。</li>
 * </ol>
 *
 * <p><b>评估顺序固定**：Spring 注入 List&lt;RiskRule&gt; 的 @Order 序（白名单短路→黑名单→
 * 设备日限），无优先级引擎——两三条规则时引擎是负担；规则多了以后再上
 * risk_rule 六表+版本灰度（V6 原文方案，登记 runbook §裁剪登记）。
 *
 * <p><b>实现纪律</b>：评估内部的依赖故障（Redis down、DB 抖动）自己 catch、
 * markDegraded、返回 empty——fail-open 的判断离故障源最近；没兜住抛出来的异常
 * 由 RiskDecisionService 兜（同样 degraded + 放行），业务永不被风控自身故障打死。
 */
public interface RiskRule {

    /** 规则稳定标识（R_ 前缀 + 大写下划线；发布后不可改名——matched_rules 的对照键） */
    String ruleId();

    /** 是否支持该场景（不支持=本次决策跳过它，不记因子） */
    boolean supports(RiskScene scene);

    /**
     * 评估一次。因子（输入与中间结果）必须写进 collector——它们是回放可解释性的来源。
     *
     * @return empty=未命中；PASS/REJECT 会终止评估；REVIEW/CHALLENGE 记录后继续
     */
    Optional<RiskAction> evaluate(RiskContext ctx, RiskFactors factors);

    /**
     * 是否可回放。**回放的定义是"无副作用地重算"**——不满足的规则返回 false，
     * 回放端点会把它列进 skippedRules（如实标注"未评估"，不假装算过）。
     *
     * <p>纯规则（只读判定，如名单）直接 true；有副作用但**日志因子足够重算**的规则
     * 也返回 true 并覆盖 {@link #replay}（如设备日限：日志里有当时的 count，
     * 拿它对**当前阈值**重新比较即可，不需要再碰计数器）。
     */
    default boolean replayable() {
        return false;
    }

    /**
     * 回放时的重算方式。默认=与线上同一条路径（{@link #evaluate}，适用于纯规则）；
     * 有副作用的规则覆盖本方法做"**日志因子 × 当前配置**"的纯重算——这是调参验证
     * 的物理基础：改了阈值/名单后回放历史行，diff 才会真的动。
     *
     * @param loggedFactors 当次决策日志里的 factors_json（反序列化后的快照）；
     *                      老行/无快照时可能为空 Map——实现要能诚实地说"算不了"（返回 empty+注明）
     */
    default Optional<RiskAction> replay(RiskContext ctx, Map<String, Object> loggedFactors,
                                        RiskFactors out) {
        return evaluate(ctx, out);
    }
}
