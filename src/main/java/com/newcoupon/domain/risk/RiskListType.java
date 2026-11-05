package com.newcoupon.domain.risk;

/**
 * 名单类型（risk_list_entry.list_type，V6 4A）。
 *
 * <p>对动作的映射是决策框架里唯一"名单→动作"的硬绑定：
 * BLACK→REJECT、WHITE→PASS（短路免评估）、GRAY→REVIEW。
 * 白名单优先于黑名单（跨维度）——运营把一个主体放进白名单是明确的意思表示，
 * 必须盖过任何一条黑名单命中（哪怕是同一个值的另一个维度）。
 */
public enum RiskListType {

    /** 黑名单：命中即 REJECT */
    BLACK,

    /** 白名单：命中即 PASS 且短路（后续规则不再评估） */
    WHITE,

    /** 灰名单：命中即 REVIEW（放行但留痕，等人工/离线复核） */
    GRAY
}
