package com.newcoupon.application.service;

import com.newcoupon.domain.risk.RiskScene;

/**
 * 风控决策上下文（V6 4A，4B 补 templateId）：一次决策的全部输入。
 *
 * <p><b>谁构建它</b>：场景接线点（领券=ReceiveAppService/ReceiveAsyncAppService/CouponCodeService，
 * 交易=TradeAppService）。appId 取**券模板归属租户**而不是调用方身份——黑名单/灰度开关按
 * “这张券是谁的”生效，两个调用面（/api 内部面、/open 开放面）行为一致；deviceId/ip 来自
 * {@code RiskRequestAttributes}（Filter 透传，服务层不摸 HttpServletRequest）；
 * userId/requestId 来自业务请求（交易场景的 requestId=该场景的幂等证据：lock=usageNo、
 * confirm=usageNo、refund=refundNo——同一意图重放可按它聚决策）。
 *
 * <p>各字段都允许缺失（除 scene/appId）：缺失的维度由规则自己声明“因子缺失”
 * （degraded），不挡请求——透传维度的既定语义。
 *
 * @param templateId 4B 新增：领券场景的业务锚（券模板 id）。预算燃烧信号要由模板找到
 *                   活动的预算账户——“这个活动烧得多快”没有模板就无从谈起。交易场景
 *                   （锁券/核销/退款）没有新模板语义，允许 null；RECEIVE 场景各接线点
 *                   都应带上（缺了=预算规则按因子缺失跳过，不挡请求）。
 */
public record RiskContext(
        RiskScene scene,
        String appId,
        Long userId,
        String deviceId,
        String ip,
        String requestId,
        Long templateId) {

    /** 4A 六参形态（既有调用点与测试不因 4B 加字段而改动） */
    public RiskContext(RiskScene scene, String appId, Long userId,
                       String deviceId, String ip, String requestId) {
        this(scene, appId, userId, deviceId, ip, requestId, null);
    }
}
