package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponDailyCounterMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponUserDailyCounterMapper;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 异步任务/日计数保留（ADR-0019 §11）：1k QPS 一天约 8640 万任务行，不清理会先于容量目标爆表。
 *
 * <p>P0-5 之后同步领取也会写两张日计数表，所以本任务**不再只在 worker 开启时注册**；
 * 否则纯同步部署会只写不删。只删终态任务（SUCCESS/REJECTED/DEAD），PENDING/PROCESSING
 * 永不删；按主键/日期分批，避免一条大 DELETE 长事务锁表。计数表按 stat_day 保留，默认 90 天。
 */
@Service
public class CouponIssueTaskCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueTaskCleanupJob.class);
    private static final String LOCK_KEY = "c:lock:issue-task-cleanup";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final CouponIssueTaskMapper taskMapper;
    private final CouponDailyCounterMapper dailyCounterMapper;
    private final CouponUserDailyCounterMapper userDailyCounterMapper;
    private final RedisScanLock scanLock;
    private final int retentionDays;
    private final int counterRetentionDays;
    private final int batchSize;
    private final int maxBatches;

    public CouponIssueTaskCleanupJob(CouponIssueTaskMapper taskMapper,
                                     CouponDailyCounterMapper dailyCounterMapper,
                                     CouponUserDailyCounterMapper userDailyCounterMapper,
                                     RedisScanLock scanLock,
                                     @Value("${coupon.issue.retention-days:7}") int retentionDays,
                                     @Value("${coupon.issue.counter-retention-days:90}") int counterRetentionDays,
                                     @Value("${coupon.issue.cleanup-batch-size:1000}") int batchSize,
                                     @Value("${coupon.issue.cleanup-max-batches:100}") int maxBatches) {
        this.taskMapper = taskMapper;
        this.dailyCounterMapper = dailyCounterMapper;
        this.userDailyCounterMapper = userDailyCounterMapper;
        this.scanLock = scanLock;
        this.retentionDays = retentionDays;
        this.counterRetentionDays = counterRetentionDays;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    @Scheduled(cron = "${coupon.issue.cleanup-cron:0 17 4 * * ?}", zone = "Asia/Shanghai")
    public void scheduledCleanup() {
        runOnce();
    }

    @XxlJob("couponIssueTaskCleanupJob")
    public void xxlCleanup() {
        runOnce();
    }

    /** @return 删除的任务行数 + 两张计数表行数。 */
    public int runOnce() {
        String owner = "issue-cleanup-" + UUID.randomUUID();
        if (!scanLock.tryLock(LOCK_KEY, owner, LOCK_TTL)) {
            log.debug("issue task cleanup skipped: another instance holds {}", LOCK_KEY);
            return 0;
        }
        try {
            LocalDateTime taskCutoff = LocalDateTime.now().minusDays(retentionDays);
            int tasks = deleteTasks(taskCutoff);

            LocalDate counterCutoff = LocalDate.now().minusDays(counterRetentionDays);
            int counters = 0;
            for (int i = 0; i < maxBatches; i++) {
                int deleted = dailyCounterMapper.deleteBefore(counterCutoff, batchSize)
                        + userDailyCounterMapper.deleteBefore(counterCutoff, batchSize);
                counters += deleted;
                if (deleted < batchSize) {
                    break;
                }
            }
            if (tasks + counters > 0) {
                log.info("issue task cleanup: tasks={}, counters={}, taskCutoff={}, counterCutoff={}",
                        tasks, counters, taskCutoff, counterCutoff);
            }
            return tasks + counters;
        } finally {
            scanLock.unlock(LOCK_KEY, owner);
        }
    }

    private int deleteTasks(LocalDateTime cutoff) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = taskMapper.deleteTerminalBefore(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
