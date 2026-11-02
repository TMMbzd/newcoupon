package com.newcoupon.application.dto;

/**
 * 批量发放任务受理结果。
 *
 * <p>idempotent=true 表示 (appId, requestId) 已有任务，本次没有新建、
 * 直接返回现状——调用方网络超时重试时拿它区分"新建"与"重放"。
 */
public record GrantTaskCreateResult(
        String taskNo,
        int status,
        long totalCount,
        boolean idempotent
) {
}
