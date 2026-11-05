package com.newcoupon.domain.risk;

/**
 * 风控决策四态（V6 阶段 4A 决策框架的统一出口）。
 *
 * <p>为什么是"四态"而不是"拦/放"两态：拦和放之间还有两种真实存在的中间处置——
 * "可疑但证据不足"（REVIEW：放行但留痕，等人工/离线信号复核）和
 * "是人是机存疑"（CHALLENGE：先验一下再继续）。只有两态时，中间态只能被
 * 挤到任一侧：放行则丢线索，拒绝则误杀真人。
 *
 * <p><b>severity（严重度）是合成多条规则结果的依据</b>：多条规则同时命中时
 * 最终动作取最严（REJECT &gt; REVIEW &gt; CHALLENGE &gt; PASS）——"名单说可疑、
 * 频控说拒绝"的答案是拒绝，而不是让先评估的规则抢先定调。
 *
 * <p><b>PASS 有两种来源</b>，语义不同但动作相同：无规则命中（默认放行）与
 * 白名单命中（明确放行、短路免评估）。区分靠决策日志的 matched_rules，
 * 不靠动作值本身。
 *
 * <p><b>CHALLENGE 在 4A 是保留位</b>：本刀没有任何规则产出它（枚举、日志列、
 * 指标标签都支持，防止将来加值时改列改文档）。完整版（挑战页/一次性 token/
 * 通过后重新决策/tianai-captcha）登记在 runbook 裁剪清单——接线时领券入口
 * 对 CHALLENGE 的处置是"返回挑战 token 占位"，不是放行也不是拒绝。
 */
public enum RiskAction {

    /** 放行（无规则命中，或白名单命中短路） */
    PASS(0),

    /** 要求人机挑战后重试（保留位：4A 无规则产出；token 占位见 RiskAction 类注释） */
    CHALLENGE(1),

    /** 放行但复核：留痕 + 指标，等人工或离线信号再定（灰名单命中的动作） */
    REVIEW(2),

    /** 拒绝（黑名单/设备超阈）。处置由场景决定：领券=抛 7003；退款（刀 2）=照做+打标（合规红线） */
    REJECT(3);

    private final int severity;

    RiskAction(int severity) {
        this.severity = severity;
    }

    /** 严重度（多规则命中取最严的合成依据）；PASS=0 … REJECT=3 */
    public int severity() {
        return severity;
    }

    /** 是否比另一个动作更严（合成时的比较方向） */
    public boolean moreSevereThan(RiskAction other) {
        return this.severity > other.severity;
    }
}
