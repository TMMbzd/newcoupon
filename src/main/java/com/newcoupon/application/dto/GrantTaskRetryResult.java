package com.newcoupon.application.dto;

/**
 * 批量发放任务"重试失败项"受理结果。
 *
 * <p>accepted=false 表示没有 FAIL item 被重置：要么失败项已被上一受理重置过
 * （幂等重放，任务已在重跑），要么本来就没有失败项——两种情况都不算错误。
 * retried 是本次实际重置的 item 数，调用方用它对账"这一轮要补发几张"。
 */
public record GrantTaskRetryResult(
        String taskNo,
        int status,
        boolean accepted,
        int retried
) {
}
