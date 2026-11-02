package com.newcoupon.application.dto;

import java.time.LocalDateTime;

/**
 * 批量发放任务进度查询结果。
 *
 * <p>percent = (total - pending) * 100 / total：已裁决（成功+失败+跳过）占比，
 * 整数精度——进度条只需要趋势，不需要小数。
 *
 * @param scheduleTime 定时开始时间（V6 阶段 1B）；null=立即执行任务。
 *                     值来自 DB DATETIME(3)（毫秒精度），调用方据此判断
 *                     "为什么 pending 不动"：是还没到点，还是 worker 没跑。
 */
public record GrantTaskStatus(
        String taskNo,
        int status,
        long totalCount,
        long successCount,
        long failCount,
        long skipCount,
        long pendingCount,
        long percent,
        LocalDateTime scheduleTime
) {
}
