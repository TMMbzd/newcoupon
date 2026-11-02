package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 异步发券的批量协调器：同模板、无日限、批内用户不重复时，把整组任务交给
 * {@link CouponIssueTaskRawBulkWriter} 用裸连接写物理表；否则退回单任务事务。
 *
 * <p>为什么批量写不在 ShardingSphere 里做：本机实测同一份 8000 行数据，裸 MySQL 约 0.76 s，
 * 经 ShardingSphere 解析/改写约 4–5 s。worker 已算好物理分片，批量路径直接拼物理表名；
 * 单任务/日限路径仍走 ShardingSphere，批量失败也逐条回退，因此不是唯一写通道。
 *
 * <p>本类刻意不加 `@Transactional`：批量写由裸连接自己管理 commit/rollback；
 * 非批量路径逐条调用 {@link CouponIssueTaskTxService}，每条任务有自己的短事务。
 */
@Service
public class CouponIssueTaskBatchTxService {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueTaskBatchTxService.class);

    private final CouponIssueTaskTxService taskTxService;
    private final CouponIssueTaskRawBulkWriter rawBulkWriter;
    private final boolean rawBulkEnabled;

    public CouponIssueTaskBatchTxService(CouponIssueTaskTxService taskTxService,
                                         CouponIssueTaskRawBulkWriter rawBulkWriter,
                                         @org.springframework.beans.factory.annotation.Value(
                                                 "${coupon.issue.worker.raw-bulk-enabled:true}")
                                         boolean rawBulkEnabled) {
        this.taskTxService = taskTxService;
        this.rawBulkWriter = rawBulkWriter;
        this.rawBulkEnabled = rawBulkEnabled;
    }

    /**
     * 启动时把生效值打出来。命令行的 {@code --coupon.issue.worker.raw-bulk-enabled}
     * 优先级高于环境变量；没有这行日志时，运维只能从 DEBUG 批次日志猜当前走哪条路。
     */
    @PostConstruct
    void logEffectiveSwitch() {
        log.info("issue task raw bulk enabled={}", rawBulkEnabled);
    }

    /** 与模板一起传入，避免批内重复读模板；seq/layoutVersion/shard 由调用方在事务外算好。 */
    public record BatchItem(CouponIssueTaskPO task, CouponTemplate template,
                            int seq, int layoutVersion, int shardNo) {
    }

    /**
     * 整块任务（可跨多个库存分片）的批量写。Processor 只在满足批量前置条件时调用。
     * 失败由 Processor 捕获并退回按分片拆组，再失败则逐条裁决。
     */
    public void processAll(List<BatchItem> items, String owner) {
        if (items.isEmpty()) {
            return;
        }
        if (!rawBulkEnabled) {
            log.debug("raw bulk disabled, processing batch with per-task transactions: size={}",
                    items.size());
            processIndividually(items, owner);
            return;
        }
        if (!bulkEligible(items)) {
            throw new IllegalStateException("processAll called with non-bulk-eligible items");
        }
        rawBulkWriter.write(items, owner);
    }

    /** 单分片组的批量写；不满足批量条件时逐条走单任务事务。 */
    public void process(List<BatchItem> items, String owner) {
        if (items.isEmpty()) {
            return;
        }
        if (rawBulkEnabled && bulkEligible(items)) {
            rawBulkWriter.write(items, owner);
            return;
        }
        processIndividually(items, owner);
    }

    private void processIndividually(List<BatchItem> items, String owner) {
        for (BatchItem item : items) {
            item.template().assertReceivable(LocalDateTime.now());
            taskTxService.process(item.task(), item.template(), item.seq(),
                    item.layoutVersion(), item.shardNo(), owner);
        }
    }

    /**
     * 批量前置条件：同一模板、无模板日限/每人日限、批内用户不重复。
     * 满足时没有日计数行锁，批量事务里只有库存分片行锁。
     */
    private boolean bulkEligible(List<BatchItem> items) {
        CouponTemplate first = items.get(0).template();
        if (first.getDailyLimit() != 0 || first.getPerUserDailyLimit() != 0) {
            return false;
        }
        Set<Long> users = new HashSet<>(items.size() * 2);
        for (BatchItem item : items) {
            // getId() 是 Long：这里必须比值，不能比引用。两个从数据库分别加载的
            // 同 id 模板对象，在 Long 缓存范围外（>127）用 != 会误判成不同模板，
            // 整批会被降级成逐条路径而不报错。
            if (!item.template().getId().equals(first.getId())
                    || item.layoutVersion() != items.get(0).layoutVersion()
                    || !users.add(item.task().getUserId())) {
                return false;
            }
        }
        return true;
    }
}
