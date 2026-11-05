package com.newcoupon.domain.risk;

/**
 * 风控决策场景（V6 4A 立枚举、4B 全量接线）：一个场景=一个被风控守护的业务入口。
 *
 * <p><b>接线矩阵（4B 后）</b>：RECEIVE=领券（同步 ReceiveAppService / 异步
 * ReceiveAsyncAppService 受理层 / 兑换码 CouponCodeService 抢占前）；LOCK=锁券、
 * CONFIRM=核销（TradeAppService，REJECT→7003）；REFUND=退款（TradeAppService）。
 * 批量/定时发放（grant worker 路径）不接——B 端运营动作非 C 端风险面（登记 runbook）。
 *
 * <p><b>REFUND 的合规红线（处置策略由场景接线点定，枚举只立语义）</b>：退款的 REJECT
 * **一律不拒绝退款**（消费者退款权 &gt; 平台风控），降级为“退款照做 + 决策日志如实记
 * REJECT + 结算冻结（holdNo=RISK-{decisionId}）”；REVIEW 只留痕不冻结。
 *
 * <p>规则用它声明自己支持哪些场景（{@code RiskRule.supports}）——
 * 设备日限/预算燃烧只对 RECEIVE 有意义（防刷券），对 REFUND 无意义（退款次数另有政策上限）；
 * 名单规则对全部场景成立。
 */
public enum RiskScene {

    /** 领券（4A 同步入口；4B 补异步受理层与兑换码抢占前——REJECT 均抛 7003） */
    RECEIVE,

    /** 锁券（4B 接线：TradeAppService.lock，参数校验/幂等快查后、快照绑定前） */
    LOCK,

    /** 核销（4B 接线：TradeAppService.doConfirm，占用单定位后、金额计算前） */
    CONFIRM,

    /** 退款（4B 接线：TradeAppService.doRefund——REJECT 降级照做+结算冻结，合规红线） */
    REFUND
}
