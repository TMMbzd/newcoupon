package com.newcoupon.application.service;

import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务终态/重试状态更新。独立 Bean 的原因：这些更新必须在**发券事务之外**执行，
 * 否则业务拒绝（库存/日限）回滚时会把“已拒绝”的状态也一起回滚。
 */
@Service
public class CouponIssueTaskStatusService {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueTaskStatusService.class);
    private static final int MAX_ERROR_MSG = 255;

    private final CouponIssueTaskMapper taskMapper;

    public CouponIssueTaskStatusService(CouponIssueTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Transactional
    public boolean markSuccess(long taskId, String owner, long couponId) {
        return taskMapper.markSuccess(taskId, owner, couponId) == 1;
    }

    @Transactional
    public boolean markRejected(long taskId, String owner, ErrorCode code, String message) {
        return taskMapper.markRejected(taskId, owner, code.getCode(), truncate(message)) == 1;
    }

    @Transactional
    public boolean markRetry(long taskId, String owner, LocalDateTime nextScanTime,
                             ErrorCode code, String message) {
        return taskMapper.markRetry(taskId, owner, nextScanTime, code.getCode(), truncate(message)) == 1;
    }

    @Transactional
    public boolean markDead(long taskId, String owner, ErrorCode code, String message) {
        return taskMapper.markDead(taskId, owner, code.getCode(), truncate(message)) == 1;
    }

    /** gate 补偿任务：释放成功/无需释放（幂等）。 */
    @Transactional
    public boolean markGateReleased(long taskId) {
        return taskMapper.markGateReleased(taskId) == 1;
    }

    /** gate 补偿任务：暂不可判定/Redis 失败，退避重试。 */
    @Transactional
    public boolean markGateRetry(long taskId, LocalDateTime next) {
        return taskMapper.markGateRetry(taskId, next) == 1;
    }

    /** DEAD 任务探到券：修复为 SUCCESS，gate 标记已签发。 */
    @Transactional
    public boolean markRecoveredSuccess(long taskId, long couponId) {
        return taskMapper.markRecoveredSuccess(taskId, couponId) == 1;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_MSG ? message : message.substring(0, MAX_ERROR_MSG);
    }

    /** 供 processor 记录“lease 已丢”的观测日志，不写库。 */
    public void logLeaseLost(long taskId, String owner) {
        log.warn("issue task lease lost, skip status update: taskId={}, owner={}", taskId, owner);
    }
}
