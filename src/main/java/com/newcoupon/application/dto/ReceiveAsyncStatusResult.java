package com.newcoupon.application.dto;

/**
 * 异步发放任务状态查询结果。
 *
 * @param requestId 业务幂等键
 * @param status    ACCEPTED / PROCESSING / SUCCESS / REJECTED / DEAD
 * @param couponId  成功后的用户券 id；未成功为 null
 * @param errorCode 拒绝/失败业务码；未失败为 null
 * @param errorMsg  拒绝/失败原因；未失败为 null
 */
public record ReceiveAsyncStatusResult(String requestId, String status,
                                       Long couponId, Integer errorCode, String errorMsg) {
}
