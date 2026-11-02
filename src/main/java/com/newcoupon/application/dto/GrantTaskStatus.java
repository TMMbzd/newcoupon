package com.newcoupon.application.dto;

/**
 * 批量发放任务进度查询结果。
 *
 * <p>percent = (total - pending) * 100 / total：已裁决（成功+失败+跳过）占比，
 * 整数精度——进度条只需要趋势，不需要小数。
 */
public record GrantTaskStatus(
        String taskNo,
        int status,
        long totalCount,
        long successCount,
        long failCount,
        long skipCount,
        long pendingCount,
        long percent
) {
}
