package com.newcoupon.application.dto;

import com.newcoupon.domain.coupon.UserCoupon;
import java.time.LocalDateTime;

/**
 * 领券结果 DTO。
 *
 * <p>duplicated 字段承载 docs/01 4.8 接口契约的 DUPLICATED 语义：
 * true = 本次请求是重放/并发命中（返回的是第一次发放的那张券），调用方**视为成功**，
 * 但可以据此区分"新领成功"与"重放命中"用于对账与排障。
 */
public record ReceiveResult(
        Long couponId,
        String templateCode,
        Long faceValue,
        LocalDateTime validEnd,
        boolean duplicated
) {
    public static ReceiveResult of(UserCoupon coupon, boolean duplicated) {
        return new ReceiveResult(coupon.getId(), coupon.getTemplateCode(),
                coupon.getFaceValue(), coupon.getValidEnd(), duplicated);
    }
}
