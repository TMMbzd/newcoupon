package com.newcoupon.application.service;

import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 异步 gate 终态补偿（ADR-0019/0021）。
 *
 * <p>worker 落 REJECTED/DEAD 后，Redis 里受理时预扣的库存 gate 和 ul/uld/dly 计数必须回退；
 * 成功任务则标记 ISSUED，永不释放。释放按错误码区分：
 * <ul>
 *   <li>STOCK_OUT(3001)：DB 确实无货，保留库存 gate，只释放日限计数；</li>
 *   <li>其他拒绝：回加库存 gate（若 key 存在）+ 释放计数；</li>
 *   <li>DEAD：先按幂等键探券；券已在（崩溃窗口）则修复成 SUCCESS，不能释放。</li>
 * </ul>
 *
 * <p>释放只认本请求的 idem 状态；key 丢失 = no-op。任务表 gate_release_state 记录结果，
 * Redis 失败则退避重试；Redis 整体丢失时 gate 重建/DB 终审兜底。
 */
@Service
@ConditionalOnProperty(prefix = "coupon.issue.gate-compensation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CouponIssueGateCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueGateCompensationJob.class);
    private static final String LOCK_KEY = "c:issue:gate-compensate:lock";
    private static final int BATCH_SIZE = 100;
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final long RETRY_BACKOFF_SECONDS = 30;

    private final CouponIssueTaskMapper taskMapper;
    private final CouponIssueTaskStatusService statusService;
    private final RedisIssueGate issueGate;
    private final RedisScanLock scanLock;
    private final UserCouponRepository userCouponRepository;
    private final long idemTtlSeconds;
    private final long dayTtlSeconds;

    public CouponIssueGateCompensationJob(CouponIssueTaskMapper taskMapper,
                                          CouponIssueTaskStatusService statusService,
                                          RedisIssueGate issueGate,
                                          RedisScanLock scanLock,
                                          UserCouponRepository userCouponRepository,
                                          @Value("${coupon.issue.idem-ttl-seconds:604800}")
                                          long idemTtlSeconds,
                                          @Value("${coupon.issue.day-ttl-seconds:172800}")
                                          long dayTtlSeconds) {
        this.taskMapper = taskMapper;
        this.statusService = statusService;
        this.issueGate = issueGate;
        this.scanLock = scanLock;
        this.userCouponRepository = userCouponRepository;
        this.idemTtlSeconds = idemTtlSeconds;
        this.dayTtlSeconds = dayTtlSeconds;
    }

    @XxlJob("couponIssueGateCompensationJob")
    @Scheduled(fixedDelayString = "${coupon.issue.gate-compensation.fixed-delay-ms:30000}",
            initialDelayString = "${coupon.issue.gate-compensation.initial-delay-ms:30000}")
    public int runOnce() {
        String owner = "issue-gate-compensate-" + UUID.randomUUID();
        if (!scanLock.tryLock(LOCK_KEY, owner, LOCK_TTL)) {
            return 0;
        }
        try {
            return compensateBatch();
        } catch (Exception e) {
            log.error("issue gate compensation round failed (next round retries)", e);
            return -1;
        } finally {
            scanLock.unlock(LOCK_KEY, owner);
        }
    }

    int compensateBatch() {
        List<CouponIssueTaskPO> tasks = taskMapper.selectGateReleaseCandidates(
                LocalDateTime.now(), BATCH_SIZE);
        int processed = 0;
        for (CouponIssueTaskPO task : tasks) {
            try {
                if (compensateOne(task)) {
                    processed++;
                }
            } catch (DataAccessException e) {
                log.warn("issue gate compensation DB error, retry: taskId={}, reason={}",
                        task.getId(), e.getMessage());
                statusService.markGateRetry(task.getId(),
                        LocalDateTime.now().plusSeconds(RETRY_BACKOFF_SECONDS));
            } catch (RuntimeException e) {
                log.error("issue gate compensation failed, retry: taskId={}", task.getId(), e);
                statusService.markGateRetry(task.getId(),
                        LocalDateTime.now().plusSeconds(RETRY_BACKOFF_SECONDS));
            }
        }
        return processed;
    }

    private boolean compensateOne(CouponIssueTaskPO task) {
        // DEAD 先探券：worker 可能在“券已提交、任务未成功”的窗口死掉。
        if (task.getStatus() != null && task.getStatus() == CouponIssueTaskPO.STATUS_DEAD) {
            Optional<UserCoupon> existing = userCouponRepository.findByIdempotentKey(
                    task.getUserId(), task.getAppId(), task.getRequestId());
            if (existing.isPresent()) {
                statusService.markRecoveredSuccess(task.getId(), existing.get().getId());
                try {
                    issueGate.markIssued(command(task));
                } catch (RuntimeException e) {
                    // DB 已标 ISSUED；Redis 失败不影响“永不释放”，下一轮/重建兜底。
                    log.warn("markIssued failed after DEAD repair: taskId={}", task.getId(), e);
                }
                log.warn("DEAD issue task repaired to SUCCESS: taskId={}, couponId={}",
                        task.getId(), existing.get().getId());
                return true;
            }
        }

        boolean stockOut = task.getErrorCode() != null
                && task.getErrorCode() == ErrorCode.STOCK_OUT.getCode();
        try {
            issueGate.releaseTerminal(command(task), !stockOut, true);
        } catch (RuntimeException e) {
            // Redis 失败不能把任务标成已释放；下一轮重试。
            throw e;
        }
        statusService.markGateReleased(task.getId());
        return true;
    }

    private RedisIssueGate.GateCommand command(CouponIssueTaskPO task) {
        return new RedisIssueGate.GateCommand(
                task.getTemplateId(),
                task.getAppId(),
                task.getUserId(),
                task.getRequestId(),
                nz(task.getGateMaxPerUser()),
                nz(task.getGatePerUserDaily()),
                nz(task.getGateDailyLimit()),
                task.getStatDay(),
                idemTtlSeconds,
                dayTtlSeconds);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
