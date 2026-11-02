package com.newcoupon.application.dto;

/**
 * 异步发放任务的对外状态（ADR-0019）。
 *
 * <p>数据库用 TINYINT 存状态，接口用可读字符串；两者只在这里映射一次，避免 Controller/
 * 查询服务各自写一套 switch。`DEAD` 是内部重试耗尽态，开放面也可以看到，但客户端应把它
 * 当作终态失败处理。
 */
public enum IssueTaskStatus {
    ACCEPTED,
    PROCESSING,
    SUCCESS,
    REJECTED,
    DEAD;

    public static IssueTaskStatus from(int status) {
        return switch (status) {
            case 0 -> ACCEPTED;
            case 1 -> PROCESSING;
            case 2 -> SUCCESS;
            case 3 -> REJECTED;
            case 4 -> DEAD;
            default -> throw new IllegalArgumentException("未知发放任务状态: " + status);
        };
    }
}
