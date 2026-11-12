package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 异步发放 worker（ADR-0019）。双通道：XXL-JOB 生产调度 + @Scheduled 降级；默认关闭。
 *
 * <p>每轮做“领取 → 按模板/分片分组处理”，不把整批放进一个大事务。任务状态、券、库存、
 * 日计数由 {@link CouponIssueTaskTxService} 在单任务事务里一起提交；同一库存分片的任务
 * 可以合进一个更短的本地事务（批量快路径），失败组仍退回逐条裁决。
 * 多个实例靠 `FOR UPDATE SKIP LOCKED` + lease 竞争，不依赖 Redis 锁。
 *
 * <p><b>为什么同一实例内还要并行处理一批任务</b>：单任务事务包含 5~6 条写 SQL，
 * WSL/单盘 MySQL 的 fsync 上限把串行 worker 钉在几十 TPS（2026-10-23 实测约 32/s）；
 * 多个数据库连接各自提交时，InnoDB group commit 能把一次 fsync 摊给多笔事务。
 * 并行度只作用于“已领取”的任务，领取仍是短事务 + SKIP LOCKED，语义不变。
 */
@Service
@ConditionalOnProperty(prefix = "coupon.issue.worker", name = "enabled", havingValue = "true")
public class CouponIssueTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueTaskWorker.class);

    private final CouponIssueTaskClaimService claimService;
    private final CouponIssueTaskProcessor processor;
    private final CouponIssueTaskMapper taskMapper;
    private final RedisIssueGate issueGate;
    private final int batchSize;
    private final int batchTxSize;
    private final int leaseSeconds;
    private final String workerId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService taskPool;
    private final ScheduledExecutorService leaseRenewer;
    /** 本实例当前处理中的 task id；只用于续租，不是任务所有权（所有权在 DB lease_owner）。 */
    private final Set<Long> activeTasks = ConcurrentHashMap.newKeySet();

    public CouponIssueTaskWorker(CouponIssueTaskClaimService claimService,
                                 CouponIssueTaskProcessor processor,
                                 CouponIssueTaskMapper taskMapper,
                                 RedisIssueGate issueGate,
                                 @Value("${coupon.issue.worker.batch-size:50}") int batchSize,
                                 @Value("${coupon.issue.worker.batch-tx-size:50}") int batchTxSize,
                                 @Value("${coupon.issue.worker.lease-seconds:30}") int leaseSeconds,
                                 @Value("${coupon.issue.worker.parallelism:4}") int parallelism,
                                 @Value("${coupon.issue.worker.id:}") String configuredId) {
        this.claimService = claimService;
        this.processor = processor;
        this.taskMapper = taskMapper;
        this.issueGate = issueGate;
        this.batchSize = batchSize;
        this.batchTxSize = Math.max(1, Math.min(batchTxSize, batchSize));
        this.leaseSeconds = leaseSeconds;
        this.workerId = configuredId == null || configuredId.isBlank()
                ? UUID.randomUUID().toString()
                : configuredId.substring(0, Math.min(64, configuredId.length()));
        int threads = Math.max(1, Math.min(parallelism, 32));
        // 守护线程：不阻止 JVM 退出；@PreDestroy 负责优雅收尾。
        this.taskPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "issue-task-worker-" + workerId.substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        this.leaseRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "issue-task-lease-" + workerId.substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(1, leaseSeconds / 3);
        this.leaseRenewer.scheduleAtFixedRate(this::renewLeases, period, period, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        leaseRenewer.shutdownNow();
        taskPool.shutdown();
        try {
            if (!taskPool.awaitTermination(10, TimeUnit.SECONDS)) {
                taskPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskPool.shutdownNow();
        }
    }

    /**
     * Redis 故障期间受理的 fallback 任务没有预扣 gate；终态后失效 gate，
     * 让下一次受理按 DB + 在途任务重建，避免 gate 长期偏高。
     */
    private void invalidateFallbackGates(List<CouponIssueTaskPO> tasks) {
        tasks.stream()
                .filter(t -> "async-redis-fallback".equals(t.getSource()))
                .map(CouponIssueTaskPO::getTemplateId)
                .distinct()
                .forEach(templateId -> {
                    try {
                        issueGate.invalidate(templateId);
                    } catch (RuntimeException e) {
                        log.warn("fallback gate invalidate failed: templateId={}", templateId, e);
                    }
                });
    }

    private void renewLeases() {
        if (activeTasks.isEmpty()) {
            return;
        }
        try {
            taskMapper.renewLease(new ArrayList<>(activeTasks), workerId,
                    LocalDateTime.now().plusSeconds(leaseSeconds));
        } catch (RuntimeException e) {
            // 续租失败不立刻放弃处理：owner CAS 仍会阻止旧 worker 提交；下一轮再续。
            log.warn("issue task lease renew failed: owner={}, active={}",
                    workerId, activeTasks.size(), e);
        }
    }

    @Scheduled(fixedDelayString = "${coupon.issue.worker.fixed-delay-ms:200}",
            initialDelayString = "${coupon.issue.worker.initial-delay-ms:1000}")
    public void scheduledTick() {
        runOnce();
    }

    @XxlJob("couponIssueTaskJob")
    public void xxlTick() {
        runOnce();
    }

    /**
     * 处理一轮。返回领取条数；正常空队列返回 0。
     *
     * <p>用 running 做单实例重入保护：@Scheduled 与 XXL-JOB 同时触发时，本轮只有一个在跑；
     * 多实例之间不靠这个锁，靠数据库行锁。
     */
    public int runOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<CouponIssueTaskPO> tasks = claimService.claim(
                    batchSize, workerId, Duration.ofSeconds(leaseSeconds));
            if (tasks.isEmpty()) {
                return 0;
            }
            List<Long> ids = tasks.stream().map(CouponIssueTaskPO::getId).toList();
            activeTasks.addAll(ids);
            try {
                // 必须先按模板分组再切块：批量快路径只读一次模板，同一块里混入其它模板会把
                // A 模板的券发成 B 模板（压测前审查抓到的真问题）。模板数通常个位数，分组成本可忽略。
                java.util.Map<Long, List<CouponIssueTaskPO>> byTemplate = new java.util.LinkedHashMap<>();
                for (CouponIssueTaskPO task : tasks) {
                    byTemplate.computeIfAbsent(task.getTemplateId(), k -> new ArrayList<>()).add(task);
                }
                List<List<CouponIssueTaskPO>> chunks = new ArrayList<>();
                for (List<CouponIssueTaskPO> group : byTemplate.values()) {
                    for (int i = 0; i < group.size(); i += batchTxSize) {
                        chunks.add(group.subList(i, Math.min(group.size(), i + batchTxSize)));
                    }
                }
                List<Callable<Void>> work = chunks.stream()
                        .<Callable<Void>>map(chunk -> () -> {
                            try {
                                processor.processBatch(chunk, workerId);
                            } catch (RuntimeException e) {
                                // processor 内部已按业务/DB 分类；这里只兜底，避免一条坏任务拖垮整轮。
                                log.error("issue task batch crashed: size={}, owner={}",
                                        chunk.size(), workerId, e);
                            }
                            return null;
                        })
                        .toList();
                try {
                    for (Future<Void> future : taskPool.invokeAll(work)) {
                        try {
                            future.get();
                        } catch (ExecutionException e) {
                            log.error("issue task worker future failed: owner={}",
                                    workerId, e.getCause());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("issue task worker interrupted: owner={}", workerId);
                }
                return tasks.size();
            } finally {
                activeTasks.removeAll(ids);
                invalidateFallbackGates(tasks);
            }
        } finally {
            running.set(false);
        }
    }
}
