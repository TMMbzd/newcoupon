package com.newcoupon.application.service;

import com.newcoupon.domain.risk.RiskScene;

/**
 * 风控决策上下文（V6 4A）：一次决策的全部输入。
 *
 * <p><b>谁构建它</b>：场景接线点（领券=ReceiveAppService）。appId 取**券模板归属租户**
 * 而不是调用方身份——黑名单/灰度开关按"这张券是谁的"生效，两个调用面（/api 内部面、
 * /open 开放面）行为一致；deviceId/ip 来自 {@code RiskRequestAttributes}（Filter 透传，
 * 服务层不摸 HttpServletRequest）；userId/requestId 来自业务请求。
 *
 * <p>各字段都允许缺失（除 scene/appId）：缺失的维度由规则自己声明"因子缺失"
 * （degraded），不挡请求——透传维度的既定语义。
 */
public record RiskContext(
        RiskScene scene,
        String appId,
        Long userId,
        String deviceId,
        String ip,
        String requestId) {
}
