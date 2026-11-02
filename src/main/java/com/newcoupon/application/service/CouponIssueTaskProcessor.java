package com.newcoupon.application.service;

import com.newcoupon.application.service.CouponIssueTaskBatchTxService.BatchItem;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 单任务与同分片批量的业务编排：模板校验 → 限领/日限 → 分片探测 → C1 落库。
 *
 * <p>本类刻意不加 `@Transactional`：真正的事务在 {@link CouponIssueTaskTxService}
 * 和 {@link CouponIssueTaskBatchTxService}。这样“分片满”可以回滚事务后换下一片，
 * “业务拒绝”也可以在事务外落 REJECTED，不会被回滚掉。失败分类原则与 C1 同步链路一致，
 * 但多了 lease 归属校验。批量快路径失败时必须回到单任务路径，由单任务路径给出准确错误码。
 */
@Service
public class CouponIssueTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueTaskProcessor.class);
    private static final int MAX_SEQ_ATTEMPTS = 3;

    private final TemplateRepository templateRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponIssueTaskTxService taskTxService;
    private final CouponIssueTaskBatchTxService batchTxService;
    private final CouponIssueTaskStatusService statusService;
    private final CouponStockShardMapper stockShardMapper;
    private final int maxRetry;
    private final int groupParallelism;
    /** 分片组并行执行器：一个分片组一个事务，不同分片组可同时提交；首次用到才创建。 */
    private volatile ExecutorService groupPool;

    public CouponIssueTaskProcessor(TemplateRepository templateRepository,
                                    UserCouponRepository userCouponRepository,
                                    CouponIssueTaskTxService taskTxService,
                                    CouponIssueTaskBatchTxService batchTxService,
                                    CouponIssueTaskStatusService statusService,
                                    CouponStockShardMapper stockShardMapper,
                                    @Value("${coupon.issue.worker.max-retry:5}") int maxRetry,
                                    @Value("${coupon.issue.worker.group-parallelism:16}") int groupParallelism) {
        this.templateRepository = templateRepository;
        this.userCouponRepository = userCouponRepository;
        this.taskTxService = taskTxService;
        this.batchTxService = batchTxService;
        this.statusService = statusService;
        this.stockShardMapper = stockShardMapper;
        this.maxRetry = maxRetry;
        this.groupParallelism = Math.max(1, Math.min(groupParallelism, 32));
    }

    private ExecutorService groupPool() {
        ExecutorService pool = groupPool;
        if (pool == null) {
            synchronized (this) {
                pool = groupPool;
                if (pool == null) {
                    pool = Executors.newFixedThreadPool(groupParallelism, r -> {
                        Thread t = new Thread(r, "issue-task-shard-group");
                        t.setDaemon(true);
                        return t;
                    });
                    groupPool = pool;
                }
            }
        }
        return pool;
    }

    @PreDestroy
    void shutdownGroupPool() {
        ExecutorService pool = groupPool;
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /**
     * 一批任务的事务快路径。只在“同一模板 + 每人日限/模板日限都为 0 + 批内用户不重复”时启用：
     * 这样批内没有日计数行锁，且每个分片组只锁一行库存记录，组间不会形成锁环。任何前置条件
     * 不满足或批量事务失败，都退回 {@link #process} 逐条执行——批量只负责加速，裁决仍由单任务路径做。
     *
     * <p>为什么不在批量里顺手处理日限：一条撞限就要回滚整批，回滚后再逐条跑一遍反而更慢；
     * 日限场景本来就有专门的 `coupon_*_daily_counter` 条件更新，逐条路径是正确的。
     */
    public void processBatch(List<CouponIssueTaskPO> tasks, String owner) {
        if (tasks.isEmpty()) {
            return;
        }
        long templateId = tasks.get(0).getTemplateId();
        for (CouponIssueTaskPO task : tasks) {
            if (!task.getTemplateId().equals(templateId)) {
                // worker 已按模板分组；这里是防御性兜底，避免未来调用方混批导致串券。
                for (CouponIssueTaskPO each : tasks) {
                    process(each, owner);
                }
                return;
            }
        }
        CouponTemplate template;
        try {
            template = templateRepository.findById(tasks.get(0).getTemplateId()).orElse(null);
        } catch (DataAccessException e) {
            for (CouponIssueTaskPO task : tasks) {
                retry(task, owner, "read template failed: " + e.getMessage());
            }
            return;
        }
        if (template == null) {
            for (CouponIssueTaskPO task : tasks) {
                statusService.markRejected(task.getId(), owner, ErrorCode.TEMPLATE_NOT_FOUND,
                        "template missing");
            }
            return;
        }
        try {
            template.assertReceivable(LocalDateTime.now());
        } catch (BizException e) {
            for (CouponIssueTaskPO task : tasks) {
                statusService.markRejected(task.getId(), owner, e.getErrorCode(), e.getMessage());
            }
            return;
        }
        int effective = template.getStockShardCount();
        if (effective < 1) {
            for (CouponIssueTaskPO task : tasks) {
                statusService.markRejected(task.getId(), owner, ErrorCode.DEGRADED, "invalid shard count");
            }
            return;
        }
        int layoutVersion = template.getStockLayoutVersion();

        boolean batchable = template.getDailyLimit() == 0 && template.getPerUserDailyLimit() == 0;
        if (batchable) {
            Set<Long> users = new HashSet<>(tasks.size() * 2);
            for (CouponIssueTaskPO task : tasks) {
                if (!users.add(task.getUserId())) {
                    batchable = false;   // 同一用户多任务会争 seq/用户日计数，交逐条路径
                    break;
                }
            }
        }

        if (batchable) {
            List<BatchItem> items = new ArrayList<>(tasks.size());
            for (CouponIssueTaskPO task : tasks) {
                // 受理时 Redis gate 已把 seq 写进任务表（WAL 的一部分）。批量路径直接用它，
                // 不再逐条 MAX(seq) 回查——2000 条任务就是 2000 次 ShardingSphere 单表查询，
                // 2026-10-23 实测这一段比真正写库还慢。seq 失效（并发/空洞）由
                // uk_user_seq 唯一索引兜底：批量失败后单任务路径会重新 MAX(seq)+1 并准确分类。
                Integer seq = task.getSeq();
                if (seq == null || seq < 1 || seq > template.getPerUserLimit()) {
                    batchable = false;   // 缺少可信 seq：交逐条路径重新计算/返回 3002
                    break;
                }
                items.add(new BatchItem(task, template, seq, layoutVersion,
                        StockShardPlan.route(task.getUserId(), effective)));
            }
            if (batchable) {
                // 快路径：整块任务一个事务，库存按分片汇总后逐片更新，券/事件/outbox/
                // 过期任务/任务状态各用多行 SQL 写掉。2000 条任务只有约 150 条 SQL。
                // 失败（某片额度不足/唯一键/租约丢失）再退回“按分片拆组”的中路径；
                // 中路径单组失败再逐条重跑。三层从快到准，不牺牲最终裁决。
                try {
                    batchTxService.processAll(items, owner);
                    // 这里不写“whole-batch committed”：raw-bulk 关闭时 processAll 是逐条事务，
                    // 路径细节由 BatchTxService 记录，避免日志把逐条回退说成一次批量提交。
                    log.debug("issue task batch completed: templateId={}, size={}",
                            template.getId(), items.size());
                    return;
                } catch (RuntimeException e) {
                    log.debug("issue task whole-batch fallback: templateId={}, size={}, reason={}",
                            template.getId(), items.size(), e.toString());
                }

                Map<Integer, List<BatchItem>> byShard = new LinkedHashMap<>();
                for (BatchItem item : items) {
                    byShard.computeIfAbsent(item.shardNo(), k -> new ArrayList<>()).add(item);
                }
                List<Callable<Void>> groupWork = new ArrayList<>(byShard.size());
                for (List<BatchItem> group : byShard.values()) {
                    groupWork.add(() -> {
                        try {
                            batchTxService.process(group, owner);
                            // 这里不写 "committed"：开关关闭时 process(group) 是逐条事务；
                            // 具体路径由 CouponIssueTaskBatchTxService 记录。
                            log.debug("issue task shard group finished: templateId={}, shard={}, size={}",
                                    template.getId(), group.get(0).shardNo(), group.size());
                        } catch (RuntimeException e) {
                            // 该分片满/日限/幂等/死锁/租约丢失：只回滚这一组，逐条重跑做准确分类。
                            log.debug("issue task shard batch fallback: templateId={}, shard={}, size={}, reason={}",
                                    template.getId(), group.get(0).shardNo(), group.size(), e.toString());
                            for (BatchItem item : group) {
                                process(item.task(), owner);
                            }
                        }
                        return null;
                    });
                }
                try {
                    for (Future<Void> future : groupPool().invokeAll(groupWork)) {
                        try {
                            future.get();
                        } catch (ExecutionException e) {
                            log.error("issue task shard group crashed: templateId={}", template.getId(), e.getCause());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("issue task shard groups interrupted: templateId={}", template.getId());
                }
                return;   // 每个分片组要么已提交，要么已逐条处理后提交/拒绝
            }
        }
        for (CouponIssueTaskPO task : tasks) {
            process(task, owner);
        }
    }

    public void process(CouponIssueTaskPO task, String owner) {
        CouponTemplate template;
        try {
            template = templateRepository.findById(task.getTemplateId()).orElse(null);
        } catch (DataAccessException e) {
            retry(task, owner, "read template failed: " + e.getMessage());
            return;
        }
        if (template == null) {
            statusService.markRejected(task.getId(), owner, ErrorCode.TEMPLATE_NOT_FOUND, "template missing");
            return;
        }
        try {
            template.assertReceivable(LocalDateTime.now());
        } catch (BizException e) {
            statusService.markRejected(task.getId(), owner, e.getErrorCode(), e.getMessage());
            return;
        }

        int effective = template.getStockShardCount();
        if (effective < 1) {
            statusService.markRejected(task.getId(), owner, ErrorCode.DEGRADED, "invalid shard count");
            return;
        }
        int layoutVersion = template.getStockLayoutVersion();
        int start = StockShardPlan.route(task.getUserId(), effective);

        for (int seqAttempt = 0; seqAttempt < MAX_SEQ_ATTEMPTS; seqAttempt++) {
            long maxSeq;
            try {
                maxSeq = userCouponRepository.maxSeqByUserAndTemplate(
                        task.getUserId(), task.getTemplateId());
            } catch (DataAccessException e) {
                retry(task, owner, "read max seq failed: " + e.getMessage());
                return;
            }
            long nextSeq = maxSeq + 1;
            if (nextSeq > template.getPerUserLimit()) {
                // 先看“这张券是不是已经在一次崩溃前发过了”：崩溃窗口里券提交了、
                // 任务状态没提交，重试时 MAX(seq) 已到上限。此时 must 补 SUCCESS，
                // 不能把已成功的请求改判成 USER_LIMIT（批量路径重跑会走到这里）。
                Optional<UserCoupon> alreadyIssued;
                try {
                    alreadyIssued = userCouponRepository.findByIdempotentKey(
                            task.getUserId(), task.getAppId(), task.getRequestId());
                } catch (DataAccessException e) {
                    retry(task, owner, "idempotency probe failed: " + e.getMessage());
                    return;
                }
                if (alreadyIssued.isPresent()) {
                    if (alreadyIssued.get().getTemplateId() == task.getTemplateId()) {
                        statusService.markSuccess(task.getId(), owner, alreadyIssued.get().getId());
                    } else {
                        statusService.markRejected(task.getId(), owner,
                                ErrorCode.REQUEST_DUPLICATED, "requestId used by another template");
                    }
                    return;
                }
                // 与同步路径一致：异步 gate 预分配的 seq 可能被拒绝后留下空洞，
                // MAX+1 超限时先找最小空闲序号；真的没有空位才判 3002。
                OptionalInt freeSeq;
                try {
                    freeSeq = findFreeSeq(task.getUserId(), task.getTemplateId(),
                            template.getPerUserLimit());
                } catch (DataAccessException e) {
                    retry(task, owner, "read used seqs failed: " + e.getMessage());
                    return;
                }
                if (freeSeq.isEmpty()) {
                    statusService.markRejected(task.getId(), owner,
                            ErrorCode.USER_LIMIT_EXCEEDED, "per-user limit reached");
                    return;
                }
                nextSeq = freeSeq.getAsInt();
            }

            boolean retrySeq = false;
            for (int probe = 0; probe < effective; probe++) {
                int shardNo = (int) (((long) start + probe) % effective);
                try {
                    taskTxService.process(task, template, (int) nextSeq, layoutVersion, shardNo, owner);
                    return;
                } catch (StockLayoutChangedException e) {
                    // 布局版本已被 reshard 推进：不是业务拒绝。事务在计数/扣库存前回滚，
                    // 任务退回 PENDING，下一轮重读模板按新版本执行；耗尽才 DEAD（gate 补偿释放）。
                    retry(task, owner, "stock layout changed: " + e.getMessage());
                    return;
                } catch (StockShardDeductRejectedException e) {
                    // 该片满/停发：事务已回滚（日计数也回滚），换下一片。
                } catch (DuplicateKeyException e) {
                    Optional<UserCoupon> existing;
                    try {
                        existing = userCouponRepository.findByIdempotentKey(
                                task.getUserId(), task.getAppId(), task.getRequestId());
                    } catch (DataAccessException probeFailure) {
                        retry(task, owner, "idempotency probe failed: " + probeFailure.getMessage());
                        return;
                    }
                    if (existing.isPresent()) {
                        if (existing.get().getTemplateId() == task.getTemplateId()) {
                            // 上次已发券但任务状态没提交（崩溃/提交后断连）：补 SUCCESS。
                            statusService.markSuccess(task.getId(), owner, existing.get().getId());
                        } else {
                            statusService.markRejected(task.getId(), owner,
                                    ErrorCode.REQUEST_DUPLICATED, "requestId used by another template");
                        }
                        return;
                    }
                    retrySeq = true;
                    break;   // uk_user_seq 冲突：换下一个 seq 重开事务
                } catch (BizException e) {
                    // 日限计数拒绝前先回查幂等券：并发/跨路径重试可能在第一次幂等查询后才提交。
                    // 只有“真的达限且没有已发券”才落 REJECTED。
                    if ((e.getErrorCode() == ErrorCode.USER_LIMIT_EXCEEDED
                            || e.getErrorCode() == ErrorCode.DAILY_LIMIT_EXCEEDED)
                            && completeIfAlreadyIssued(task, owner)) {
                        return;
                    }
                    statusService.markRejected(task.getId(), owner, e.getErrorCode(), e.getMessage());
                    return;
                } catch (IssueTaskLeaseLostException e) {
                    statusService.logLeaseLost(task.getId(), owner);
                    return;
                } catch (DataAccessException e) {
                    retry(task, owner, "issue task DB failure: " + e.getMessage());
                    return;
                }
            }
            if (!retrySeq) {
                // 券可能已经在一次崩溃前提交、任务状态没提交；库存恰好也满了。
                // 先按 requestId 回查，命中就补 SUCCESS，不把已成功的请求改判售罄。
                if (completeIfAlreadyIssued(task, owner)) {
                    return;
                }
                // reshard/人工冻结窗口：active version 全部 status=1。这不是售罄，
                // 任务必须退回 PENDING，等布局切换完成/解冻后在同一任务上重试。
                long active;
                try {
                    active = stockShardMapper.countActive(task.getTemplateId(), layoutVersion);
                } catch (DataAccessException e) {
                    retry(task, owner, "read shard active count failed: " + e.getMessage());
                    return;
                }
                if (active == 0) {
                    retry(task, owner, "stock layout frozen (likely reshard), retry after layout switch");
                    return;
                }
                statusService.markRejected(task.getId(), owner, ErrorCode.STOCK_OUT, "all shards full");
                return;
            }
        }

        // 序号连续冲突（持久空洞/极端并发）：交给下一轮重试，不谎报用户达限。
        retry(task, owner, "seq retry exhausted");
    }

    /** 冷路径：MAX(seq)+1 超限时找最小空闲序号；已领满返回 empty。 */
    private OptionalInt findFreeSeq(long userId, long templateId, int limit) {
        return UserSeqAllocator.firstFree(
                userCouponRepository.findSeqsByUserAndTemplate(userId, templateId), limit);
    }

    /**
     * 失败分类前回查幂等券。
     *
     * @return true 表示已处理（补 SUCCESS、按重复请求拒绝，或探针失败已安排重试）；
     *         false 表示确实没有已发券，调用方继续走原来的拒绝分类。
     */
    private boolean completeIfAlreadyIssued(CouponIssueTaskPO task, String owner) {
        Optional<UserCoupon> existing;
        try {
            existing = userCouponRepository.findByIdempotentKey(
                    task.getUserId(), task.getAppId(), task.getRequestId());
        } catch (DataAccessException e) {
            retry(task, owner, "idempotency probe failed: " + e.getMessage());
            return true;
        }
        if (existing.isEmpty()) {
            return false;
        }
        if (existing.get().getTemplateId() == task.getTemplateId()) {
            statusService.markSuccess(task.getId(), owner, existing.get().getId());
        } else {
            statusService.markRejected(task.getId(), owner,
                    ErrorCode.REQUEST_DUPLICATED, "requestId used by another template");
        }
        return true;
    }

    private void retry(CouponIssueTaskPO task, String owner, String message) {
        int current = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int next = current + 1;
        if (next >= maxRetry) {
            statusService.markDead(task.getId(), owner, ErrorCode.SYSTEM_ERROR, message);
            return;
        }
        long delaySeconds = Math.min(60L, 1L << Math.min(next, 6));
        log.warn("issue task retry: taskId={}, retry={}/{}, delay={}s, reason={}",
                task.getId(), next, maxRetry, delaySeconds, message);
        statusService.markRetry(task.getId(), owner,
                LocalDateTime.now().plusSeconds(delaySeconds), ErrorCode.SYSTEM_ERROR, message);
    }
}
