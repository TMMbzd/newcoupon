package com.newcoupon.application.service;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantItemMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponGrantChunkPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantItemPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantTaskPO;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 批量发放 worker（V6 阶段 1A 骨架）。双通道：XXL-JOB 生产调度 + @Scheduled 降级；
 * 默认关闭（与 {@link CouponIssueTaskWorker} 相同的开关纪律）。
 *
 * <p>每轮在时间盒（默认 60s）内循环「领取一个 chunk → 逐 item 调用 C1 领取 →
 * item 记账短事务 → chunk 收尾短事务」。多实例竞争靠 DB 的
 * `FOR UPDATE SKIP LOCKED` + lease；RedisScanLock 只是把"每轮谁在跑"变成
 * 单实例省力（正确性不依赖它——丢了锁双跑，CAS 也会把结果收敛一致）。
 *
 * <p><b>错误分类</b>（对齐 {@link C1ReceiveAppService} 抛出的码）：
 * <ul>
 *   <li>成功（含 duplicated 幂等重放）→ item SUCCESS；</li>
 *   <li>终态类：3001 售罄 / 3003 日限 / 2001/2002/2003 模板不可领 → item FAIL；</li>
 *   <li>3002 已限领 → item SKIP：任务内每用户只有一张目标券且去重过，
 *       收到 3002 说明该用户已在**别的任务/入口**拿满同模板券——语义是
 *       "无需再发"而不是"发失败"；</li>
 *   <li>可重试类：5000/5001/5002/5003 → item 保持 PENDING、attempt+1、
 *       chunk 退避后重领；重试耗尽转 FAIL。</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "coupon.grant.worker", name = "enabled", havingValue = "true")
public class GrantTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(GrantTaskWorker.class);

    /** Redis 单飞锁的键；TTL 10 分钟 &gt; 时间盒 60s，一轮跑不完锁也不会中途消失。 */
    static final String SCAN_LOCK_KEY = "c:lock:grant-task";
    private static final Duration SCAN_LOCK_TTL = Duration.ofMinutes(10);

    private final GrantTaskTxService txService;
    private final C1ReceiveAppService receiveService;
    private final TemplateRepository templateRepository;
    private final CouponGrantTaskMapper taskMapper;
    private final CouponGrantItemMapper itemMapper;
    private final CouponCodeService couponCodeService;
    private final RedisScanLock scanLock;
    private final String workerId;
    private final long budgetMs;
    private final long backoffBaseMs;
    private final int itemMaxAttempts;
    private final int leaseSeconds;
    /** 单实例重入保护：@Scheduled 与 XXL-JOB 同时触发时本轮只跑一个。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GrantTaskWorker(GrantTaskTxService txService,
                           C1ReceiveAppService receiveService,
                           TemplateRepository templateRepository,
                           CouponGrantTaskMapper taskMapper,
                           CouponGrantItemMapper itemMapper,
                           CouponCodeService couponCodeService,
                           RedisScanLock scanLock,
                           @Value("${coupon.grant.worker.budget-ms:60000}") long budgetMs,
                           @Value("${coupon.grant.worker.retry-backoff-base-ms:1000}") long backoffBaseMs,
                           @Value("${coupon.grant.worker.item-max-attempts:8}") int itemMaxAttempts,
                           @Value("${coupon.grant.worker.lease-seconds:300}") int leaseSeconds,
                           @Value("${coupon.grant.worker.id:}") String configuredId) {
        this.txService = txService;
        this.receiveService = receiveService;
        this.templateRepository = templateRepository;
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.couponCodeService = couponCodeService;
        this.scanLock = scanLock;
        this.budgetMs = budgetMs;
        this.backoffBaseMs = backoffBaseMs;
        this.itemMaxAttempts = itemMaxAttempts;
        this.leaseSeconds = leaseSeconds;
        this.workerId = configuredId == null || configuredId.isBlank()
                ? UUID.randomUUID().toString()
                : configuredId.substring(0, Math.min(64, configuredId.length()));
    }

    @Scheduled(fixedDelayString = "${coupon.grant.worker.fixed-delay-ms:5000}",
            initialDelayString = "${coupon.grant.worker.initial-delay-ms:10000}")
    public void scheduledTick() {
        runOnce();
    }

    @XxlJob("couponGrantTaskJob")
    public void xxlTick() {
        runOnce();
    }

    /**
     * 标准一轮：配置时间盒内能跑多少 chunk 跑多少。返回本轮处理的 chunk 数。
     */
    public int runOnce() {
        return runFor(Duration.ofMillis(budgetMs), Integer.MAX_VALUE);
    }

    /**
     * 测试钩子：最多处理 maxChunks 个 chunk——取消用例用它精确"跑一半"。
     */
    public int runChunks(int maxChunks) {
        return runFor(Duration.ofMillis(budgetMs), maxChunks);
    }

    private int runFor(Duration budget, int maxChunks) {
        // 先兜底收尸：上一轮收尾竞态让路的任务在这里补收尾，否则永久 RUNNING
        try {
            int reaped = txService.reapStuckTasks();
            if (reaped > 0) {
                log.warn("grant task reap: {} task(s) finalized by backstop", reaped);
            }
        } catch (RuntimeException e) {
            log.warn("grant task reap failed (continue claiming)", e);
        }
        // 兑换码 LOCKED 残留顺带回收（V6 阶段 1B）：两域同属低频后台作业，
        // 开了 grant worker 的部署不再需要单独打开 coupon.code.reap-enabled。
        // 失败不阻塞本轮发放——残留码只是延后到下一轮再回收
        try {
            int codesReaped = couponCodeService.reapStaleLockedCodes();
            if (codesReaped > 0) {
                log.warn("code reap: {} stale LOCKED code(s) restored to ACTIVE", codesReaped);
            }
        } catch (RuntimeException e) {
            log.warn("code reap failed (continue claiming)", e);
        }
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            // Redis 单飞锁只是省力（省掉多实例重复扫描）：抢不到就让路等下一轮；
            // Redis 故障时降级为无锁继续——正确性由 DB 行锁 + item CAS 兜底，
            // 双跑最多浪费扫描量，不会重复发放/重复计数
            String lockOwner = "grant-worker-" + UUID.randomUUID();
            boolean holdsLock;
            try {
                holdsLock = scanLock.tryLock(SCAN_LOCK_KEY, lockOwner, SCAN_LOCK_TTL);
            } catch (RuntimeException e) {
                log.warn("grant scan lock unavailable, proceed without it (DB CAS guarded)", e);
                holdsLock = true;
            }
            if (!holdsLock) {
                return 0;
            }
            int processed = 0;
            long deadline = System.nanoTime() + budget.toNanos();
            try {
                while (processed < maxChunks && System.nanoTime() < deadline) {
                    CouponGrantChunkPO chunk;
                    try {
                        chunk = txService.claimChunk(workerId, Duration.ofSeconds(leaseSeconds));
                    } catch (RuntimeException e) {
                        log.error("grant chunk claim failed, stop this round", e);
                        break;
                    }
                    if (chunk == null) {
                        break;   // 无到期 chunk（空队列或都在退避）
                    }
                    processed++;
                    try {
                        executeChunk(chunk, deadline);
                    } catch (RuntimeException e) {
                        // 兜底：executeItem 内部已分类业务异常，到这里是环境级故障。
                        // 释放租约让下一轮重领——item 幂等保证续跑不重复发放
                        log.error("grant chunk crashed: chunkId={}, taskId={}",
                                chunk.getId(), chunk.getTaskId(), e);
                        try {
                            txService.releaseChunk(chunk.getId(), workerId);
                        } catch (RuntimeException releaseError) {
                            log.warn("grant chunk release failed, lease will expire: chunkId={}",
                                    chunk.getId(), releaseError);
                        }
                    }
                }
                return processed;
            } finally {
                try {
                    scanLock.unlock(SCAN_LOCK_KEY, lockOwner);
                } catch (RuntimeException e) {
                    // 释放失败靠 TTL 过期；持锁标识是本轮的 uuid，不会误删他人锁
                    log.debug("grant scan lock unlock failed, TTL will expire", e);
                }
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * 执行一个 chunk：逐 item 调 C1 领取并记账。任务已取消时只做取消收尾；
     * 每个 item 前复查任务状态，取消最多再"漏发"当前这一个 item 的窗口。
     */
    private void executeChunk(CouponGrantChunkPO chunk, long deadlineNanos) {
        CouponGrantTaskPO task = taskMapper.selectById(chunk.getTaskId());
        if (task == null) {
            // 任务行不存在（理论不可能）：按取消收尾防 chunk 永远悬挂
            log.error("grant chunk references missing task: chunkId={}, taskId={}",
                    chunk.getId(), chunk.getTaskId());
            txService.cancelChunk(chunk, workerId);
            return;
        }
        if (task.getStatus() == CouponGrantTaskPO.STATUS_CANCELING
                || task.getStatus() == CouponGrantTaskPO.STATUS_CANCELED) {
            txService.cancelChunk(chunk, workerId);
            return;
        }
        CouponTemplate template = templateRepository.findById(task.getTemplateId()).orElse(null);
        if (template == null) {
            // 模板被物理删除（理论不可能）：整 chunk 记终态 FAIL，不让任务悬挂
            log.error("grant task template missing: taskNo={}, templateId={}",
                    task.getTaskNo(), task.getTemplateId());
            for (CouponGrantItemPO item : itemMapper
                    .selectPendingByChunk(task.getId(), chunk.getChunkNo())) {
                txService.completeItemFail(chunk, task.getId(), item.getId(),
                        ErrorCode.TEMPLATE_NOT_FOUND.getCode(), "模板不存在");
            }
            txService.finishChunkPass(chunk, workerId, backoffUntil(chunk.getAttempt()));
            return;
        }

        List<CouponGrantItemPO> items = itemMapper
                .selectPendingByChunk(task.getId(), chunk.getChunkNo());
        boolean canceled = false;
        for (CouponGrantItemPO item : items) {
            if (System.nanoTime() > deadlineNanos) {
                // 时间盒耗尽：释放租约但立即可重领，下一轮从剩余 PENDING 接着跑
                txService.releaseChunk(chunk.getId(), workerId);
                return;
            }
            // 逐 item 复查任务状态：取消被受理后尽快停发（漏发窗口 ≤ 1 item）
            Integer status = taskMapper.selectStatus(task.getId());
            if (status != null && (status == CouponGrantTaskPO.STATUS_CANCELING
                    || status == CouponGrantTaskPO.STATUS_CANCELED)) {
                canceled = true;
                break;
            }
            // MVP 限速：item 间固定间隔 1000/rateLimit 毫秒。简单但不精确
            // （sleep 不计入发放耗时），够保护下游；精细化换令牌桶在后续刀
            Integer rateLimit = task.getRateLimit();
            if (rateLimit != null && rateLimit > 0) {
                try {
                    Thread.sleep(1000L / rateLimit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    txService.releaseChunk(chunk.getId(), workerId);
                    return;
                }
            }
            executeItem(task, template, chunk, item);
        }
        if (canceled) {
            txService.cancelChunk(chunk, workerId);
            return;
        }
        txService.finishChunkPass(chunk, workerId, backoffUntil(chunk.getAttempt()));
    }

    /**
     * 单个 item 的发放与记账分类。
     * 幂等键 grant-{taskNo}-{seq} 是确定性的：崩溃重放/租约竞态双跑时，
     * receive 对同一键返回同一张券（duplicated），记账只记一次。
     */
    private void executeItem(CouponGrantTaskPO task, CouponTemplate template,
                             CouponGrantChunkPO chunk, CouponGrantItemPO item) {
        String requestId = itemIdempotentKey(task.getTaskNo(), item.getSeq());
        try {
            ReceiveResult result = receiveService.receive(
                    new ReceiveRequest(requestId, item.getUserId(), task.getTemplateId()),
                    template);
            // duplicated=true 也是成功：幂等键命中=这张券在之前轮次（或崩溃前）已发出
            txService.completeItemSuccess(chunk, task.getId(), item.getId(), result.couponId());
        } catch (BizException e) {
            ErrorCode code = e.getErrorCode();
            if (isRetryable(code)) {
                if (item.getAttempt() + 1 >= itemMaxAttempts) {
                    // 重试耗尽：转终态 FAIL，防止瞬时故障把任务永远拖在 RUNNING
                    txService.completeItemFail(chunk, task.getId(), item.getId(),
                            code.getCode(), "重试耗尽: " + e.getMessage());
                } else {
                    txService.retryItem(item.getId(), code.getCode(), e.getMessage());
                }
            } else if (code == ErrorCode.USER_LIMIT_EXCEEDED) {
                // 3002 已限领：任务内用户已去重、各只发一张，收到它说明该用户已在
                // 别的任务/入口拿满同模板券——语义是"无需再发"，归 SKIP（未发放）
                txService.completeItemSkip(chunk, task.getId(), item.getId(),
                        code.getCode(), "用户已达限领（已在别处领取同模板券）");
            } else {
                // 3001 售罄 / 3003 日限 / 2001~2003 模板不可领：终态 FAIL，明细可查
                txService.completeItemFail(chunk, task.getId(), item.getId(),
                        code.getCode(), e.getMessage());
            }
        } catch (RuntimeException e) {
            // receive 已把 DataAccess 类翻译成 SYSTEM_ERROR，这里只剩真正的意外。
            // 与 Biz 可重试分支同款 attempt 封顶：持续的非 Biz 异常（代码 bug、
            // 熔断器抛出的其它异常）不能让 item 无限重试把任务永远拖在 RUNNING
            log.error("grant item unexpected failure: taskNo={}, itemId={}",
                    task.getTaskNo(), item.getId(), e);
            if (item.getAttempt() + 1 >= itemMaxAttempts) {
                txService.completeItemFail(chunk, task.getId(), item.getId(),
                        ErrorCode.SYSTEM_ERROR.getCode(),
                        "重试耗尽(系统异常): " + e.getClass().getSimpleName());
            } else {
                txService.retryItem(item.getId(), ErrorCode.SYSTEM_ERROR.getCode(),
                        "系统异常: " + e.getClass().getSimpleName());
            }
        }
    }

    /** 可重试码：系统/降级/冻结/预算耗尽——瞬时故障，退避后重试可能成功。 */
    private static boolean isRetryable(ErrorCode code) {
        return code == ErrorCode.SYSTEM_ERROR || code == ErrorCode.DEGRADED
                || code == ErrorCode.FROZEN || code == ErrorCode.REQUEST_BUDGET_EXHAUSTED;
    }

    /** 退避时长：基线 × 2^轮次，封顶 60s（避免长退避把任务拖到天级）。 */
    private LocalDateTime backoffUntil(int attempt) {
        int shift = Math.min(Math.max(attempt, 0), 6);
        long backoffMs = Math.min(backoffBaseMs << shift, 60_000L);
        return LocalDateTime.now().plusNanos(backoffMs * 1_000_000L);
    }

    /** receive 幂等键：grant-{taskNo}-{seq}——确定性派生，重放安全。 */
    static String itemIdempotentKey(String taskNo, int seq) {
        return "grant-" + taskNo + "-" + seq;
    }
}
