package com.newcoupon.application.dto;

/**
 * 异步受理结果。HTTP 202 只表示任务已持久化，不代表已经发券。
 *
 * @param requestId 业务幂等键
 * @param taskId    任务表主键，后续状态查询/排障用
 * @param status    ACCEPTED（新受理）或已有任务的当前状态（幂等重放）
 */
public record ReceiveAsyncResult(String requestId, Long taskId, String status) {
}
