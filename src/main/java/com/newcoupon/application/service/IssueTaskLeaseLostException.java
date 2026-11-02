package com.newcoupon.application.service;

/**
 * 发券事务提交前发现任务 lease 已不属于当前 worker：整个事务回滚，让新 worker 重做。
 * 它是控制流信号，不是业务错误，不写 error_code。
 */
public class IssueTaskLeaseLostException extends RuntimeException {

    public IssueTaskLeaseLostException(long taskId, String owner) {
        super("issue task lease lost: taskId=" + taskId + ", owner=" + owner);
    }
}
