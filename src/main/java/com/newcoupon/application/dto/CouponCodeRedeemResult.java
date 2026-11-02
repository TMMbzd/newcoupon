package com.newcoupon.application.dto;

import java.time.LocalDateTime;

/**
 * 兑换码兑换结果（V6 阶段 1B）。字段与 {@link ReceiveResult} 对齐
 * （发券核心就是 C1 receive），另带 batchNo 供调用方对账批次。
 *
 * <p>duplicated=true：本次是幂等重放（同 requestId 的上次兑换已发券，
 * 返回的是那一张原券）——视为成功，可用于对账区分"新兑换"与"重试命中"。
 */
public record CouponCodeRedeemResult(
        String batchNo,
        Long couponId,
        String templateCode,
        Long faceValue,
        LocalDateTime validEnd,
        boolean duplicated
) {
}
