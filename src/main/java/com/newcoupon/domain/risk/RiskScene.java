package com.newcoupon.domain.risk;

/**
 * 风控决策场景（V6 4A）：一个场景=一个被风控守护的业务入口。
 *
 * <p><b>4A 只接线 {@link #RECEIVE}（领券同步主入口）</b>。异步受理（/receive-async）
 * 与兑换码（redeem）本刀不接——一刀不改所有热路径，逐场景评估接线是刀 2 的工作
 * （登记 runbook §接线范围）。LOCK/CONFIRM/REFUND 同为刀 2：其中 REFUND 有
 * 合规红线——退款的 REJECT 必须**降级**为"退款照做 + 打标/退券挂起/结算调整"，
 * 不能拒绝消费者退款；所以场景枚举先立起来，处置策略由各场景的接线点自己定。
 *
 * <p>规则用它声明自己支持哪些场景（{@code RiskRule.supports}）——
 * 设备日限只对 RECEIVE 有意义（防刷券），对 REFUND 无意义（退款次数另有政策上限）。
 */
public enum RiskScene {

    /** 领券（4A 已接线：ReceiveAppService 同步入口，参数校验后、事务前） */
    RECEIVE,

    /** 锁券（预留：刀 2 TradeAppService.lock） */
    LOCK,

    /** 核销（预留：刀 2 TradeAppService.confirm） */
    CONFIRM,

    /** 退款（预留：刀 2 TradeAppService.refund——REJECT 降级照做+打标，合规红线） */
    REFUND
}
