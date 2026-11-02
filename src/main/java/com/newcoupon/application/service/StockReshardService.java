package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockAdjustLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import com.newcoupon.infrastructure.persistence.po.CouponStockAdjustLogPO;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 离线库存重排（layout_version Slice B）：冻结 active → 软排空 PROCESSING →
 * 一个事务内搬**剩余额度**到新版本 → 解冻；以及对应的 rollback。
 *
 * <p><b>不做的事</b>：不在线重排、不搬 used、不删退役行、不动 PENDING 任务、
 * 不提供 HTTP 端点。调用方是运维脚本/内部管理面（本轮未接线）。
 *
 * <p><b>失败立场</b>：
 * <ul>
 *   <li>事务提交前失败：回滚事务 + 把旧版本恢复为可卖（若模板仍发布）；</li>
 *   <li>事务提交后解冻失败：保持冻结（安全），日志 ERROR 并做 3 次短重试；</li>
 *   <li>回滚时新版本已出券：拒绝，恢复新版本可卖，只能 forward-fix v3。</li>
 * </ul>
 *
 * <p><b>幂等</b>：复用 {@code coupon_stock_adjust_log.uk_tpl_request(template_id, request_id)}。
 * 审计 reason 以 {@code RESHARD } / {@code ROLLBACK } 开头；同 requestId 重放直接返回历史结果，
 * 不同目标/不同操作类型 → STATE_CONFLICT。并发同 requestId 由唯一键终审。
 */
@Service
public class StockReshardService {

    private static final Logger log = LoggerFactory.getLogger(StockReshardService.class);

    private static final Duration LOCK_TTL = Duration.ofMinutes(30);
    private static final long DRAIN_TIMEOUT_MS = 30_000L;
    private static final long DRAIN_INTERVAL_MS = 200L;
    private static final int UNFREEZE_ATTEMPTS = 3;

    private static final Pattern RESHARD_REASON = Pattern.compile(
            "RESHARD v(\\d+)->v(\\d+) shards=(\\d+)->(\\d+) target=(\\d+) "
                    + "total=(\\d+) used=(\\d+) remaining=(\\d+)");
    private static final Pattern ROLLBACK_REASON = Pattern.compile(
            "ROLLBACK v(\\d+)->v(\\d+) shards=(\\d+)->(\\d+) total=(\\d+) used=(\\d+) remaining=(\\d+)");

    private final RedisScanLock scanLock;
    private final StringRedisTemplate redis;
    private final CachedTemplateReader cachedTemplateReader;
    private final RedisIssueGate issueGate;
    private final TemplateMapper templateMapper;
    private final CouponStockShardMapper shardMapper;
    private final CouponStockAdjustLogMapper logMapper;
    private final CouponIssueTaskMapper taskMapper;
    private final StockReshardTxService txService;
    /** 裸 MySQL 连接：information_schema 查询不能走 ShardingSphere（SQL federation 不支持）。 */
    private final DataSource rawDataSource;

    public StockReshardService(RedisScanLock scanLock,
                               StringRedisTemplate redis,
                               CachedTemplateReader cachedTemplateReader,
                               RedisIssueGate issueGate,
                               TemplateMapper templateMapper,
                               CouponStockShardMapper shardMapper,
                               CouponStockAdjustLogMapper logMapper,
                               CouponIssueTaskMapper taskMapper,
                               StockReshardTxService txService,
                               @Lazy @Qualifier("rawBulkDataSource") DataSource rawDataSource) {
        this.scanLock = scanLock;
        this.redis = redis;
        this.cachedTemplateReader = cachedTemplateReader;
        this.issueGate = issueGate;
        this.templateMapper = templateMapper;
        this.shardMapper = shardMapper;
        this.logMapper = logMapper;
        this.taskMapper = taskMapper;
        this.txService = txService;
        this.rawDataSource = rawDataSource;
    }

    /**
     * 正向重排：N→M，只搬剩余额度；used 留在旧版本，旧行退役为 {@code status=1, quota=used}。
     */
    public ReshardResult reshard(long templateId, int targetShardCount,
                                 String requestId, String operator) {
        validateCommon(templateId, requestId);
        if (targetShardCount < 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "targetShardCount 必须 >= 1");
        }
        // 安全上限：避免一次误操作按 total 建出百万行。Phase 3 的布局是 64，1024 足够。
        if (targetShardCount > 1024) {
            throw new BizException(ErrorCode.PARAM_ERROR, "targetShardCount 上限为 1024");
        }
        String owner = "stock-reshard-" + UUID.randomUUID();
        acquireLock(templateId, owner);
        try {
            CouponStockAdjustLogPO existing = logMapper.selectByRequest(templateId, requestId);
            if (existing != null) {
                return replayReshard(existing, targetShardCount);
            }
            assertLegacyIndexGone();

            CouponTemplatePO template = requireTemplate(templateId);
            if (template.getPublishStatus() == null || template.getPublishStatus() != 1) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "仅已发布模板可重排");
            }
            int fromVersion = layoutVersion(template);

            // ① 冻结 active version 并提交：worker 从下一次扣减开始只能得到 0 行。
            txService.setStatus(templateId, fromVersion, 1);
            invalidateCaches(templateId);
            log.warn("stock reshard frozen: templateId={}, version={}", templateId, fromVersion);

            // ② 软排空：只等 PROCESSING，最多 30s；PENDING 不等待（它们会在新版本上执行）。
            long processing = drainProcessing(templateId);

            try {
                StockReshardTxService.ReshardOutcome outcome = txService.executeReshard(
                        templateId, targetShardCount, requestId, operator);
                boolean active = settleAfterSwitch(templateId, outcome.toVersion());
                invalidateCaches(templateId);
                log.warn("stock reshard done: templateId={}, v{}->v{}, shards {}->{}, "
                                + "total={}, used={}, processingAtDrain={}, active={}",
                        templateId, outcome.fromVersion(), outcome.toVersion(),
                        outcome.fromShards(), outcome.toShards(), outcome.totalCount(),
                        outcome.usedCount(), processing, active);
                return new ReshardResult(templateId, outcome.fromVersion(), outcome.toVersion(),
                        outcome.fromShards(), outcome.toShards(), outcome.totalCount(),
                        outcome.usedCount(), outcome.remaining(), active, false);
            } catch (DuplicateKeyException e) {
                CouponStockAdjustLogPO concurrent = findLogQuietly(templateId, requestId);
                if (concurrent != null && isReshardLog(concurrent)) {
                    // 并发同 requestId 的赢家已提交；本事务已回滚，直接重放。
                    return replayReshard(concurrent, targetShardCount);
                }
                restoreVersion(templateId, fromVersion);
                throw e;
            } catch (RuntimeException e) {
                restoreVersion(templateId, fromVersion);
                // 并发同 requestId 可能在我们的 CAS 之前提交（本事务因 CAS 0 行失败），
                // 此时审计行已存在：按幂等重放，而不是把合法重试报成冲突。
                CouponStockAdjustLogPO concurrent = findLogQuietly(templateId, requestId);
                if (concurrent != null && isReshardLog(concurrent)) {
                    return replayReshard(concurrent, targetShardCount);
                }
                throw e;
            }
        } finally {
            releaseLock(templateId, owner);
        }
    }

    /**
     * 回滚：仅当新版本 {@code Σused=0}。已出券只能 forward-fix；拒绝时恢复新版本可卖。
     */
    public RollbackResult rollback(long templateId, int expectedNewVersion,
                                   String requestId, String operator) {
        validateCommon(templateId, requestId);
        if (expectedNewVersion < 2) {
            throw new BizException(ErrorCode.PARAM_ERROR, "expectedNewVersion 必须 >= 2");
        }
        String owner = "stock-rollback-" + UUID.randomUUID();
        acquireLock(templateId, owner);
        try {
            CouponStockAdjustLogPO existing = logMapper.selectByRequest(templateId, requestId);
            if (existing != null) {
                return replayRollback(existing, expectedNewVersion);
            }
            CouponTemplatePO template = requireTemplate(templateId);
            int currentVersion = layoutVersion(template);
            if (currentVersion != expectedNewVersion) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "布局版本已变化：expected=" + expectedNewVersion + ", actual=" + currentVersion);
            }

            // 冻结新版本：并发领券在下一次扣减时得到 0 行并走重试/分类。
            txService.setStatus(templateId, expectedNewVersion, 1);
            invalidateCaches(templateId);

            try {
                StockReshardTxService.RollbackOutcome outcome = txService.executeRollback(
                        templateId, expectedNewVersion, requestId, operator);
                boolean active = settleAfterSwitch(templateId, outcome.toVersion());
                invalidateCaches(templateId);
                log.warn("stock reshard rollback done: templateId={}, v{}->v{}, shards {}->{}",
                        templateId, outcome.fromVersion(), outcome.toVersion(),
                        outcome.fromShards(), outcome.toShards());
                return new RollbackResult(templateId, outcome.fromVersion(), outcome.toVersion(),
                        outcome.fromShards(), outcome.toShards(), outcome.totalCount(),
                        outcome.usedCount(), outcome.remaining(), active, false);
            } catch (DuplicateKeyException e) {
                CouponStockAdjustLogPO concurrent = findLogQuietly(templateId, requestId);
                if (concurrent != null && isRollbackLog(concurrent)) {
                    return replayRollback(concurrent, expectedNewVersion);
                }
                restoreVersion(templateId, expectedNewVersion);
                throw e;
            } catch (RuntimeException e) {
                restoreVersion(templateId, expectedNewVersion);
                CouponStockAdjustLogPO concurrent = findLogQuietly(templateId, requestId);
                if (concurrent != null && isRollbackLog(concurrent)) {
                    return replayRollback(concurrent, expectedNewVersion);
                }
                throw e;
            }
        } finally {
            releaseLock(templateId, owner);
        }
    }

    // ------------------------------------------------------------------
    // 冻结/解冻/失效
    // ------------------------------------------------------------------

    /**
     * 切换完成后的状态落位：模板仍发布 → 新/旧版本 status=0；已下线 → status=1。
     * 3 次短重试；仍失败保持冻结（安全）并返回 false。
     */
    private boolean settleAfterSwitch(long templateId, int layoutVersion) {
        for (int attempt = 0; attempt < UNFREEZE_ATTEMPTS; attempt++) {
            try {
                CouponTemplatePO template = requireTemplate(templateId);
                int status = template.getPublishStatus() != null
                        && template.getPublishStatus() == 1 ? 0 : 1;
                txService.setStatus(templateId, layoutVersion, status);
                return status == 0;
            } catch (RuntimeException e) {
                log.warn("settle layout status failed: templateId={}, version={}, attempt={}",
                        templateId, layoutVersion, attempt + 1, e);
                sleep(50L * (attempt + 1));
            }
        }
        log.error("SETTLE LAYOUT STATUS FAILED; leaving frozen: templateId={}, version={}",
                templateId, layoutVersion);
        return false;
    }

    /** 事务失败后恢复指定版本：仍发布→status=0；已下线→status=1。只在该版本仍是当前版本时执行。 */
    private void restoreVersion(long templateId, int layoutVersion) {
        try {
            CouponTemplatePO template = requireTemplate(templateId);
            if (layoutVersion(template) != layoutVersion) {
                return;   // 并发赢家已推进版本，不能把旧/新版本误恢复
            }
            settleAfterSwitch(templateId, layoutVersion);
        } catch (RuntimeException e) {
            log.error("RESTORE LAYOUT VERSION FAILED; layout may stay frozen: templateId={}, version={}",
                    templateId, layoutVersion, e);
        } finally {
            invalidateCaches(templateId);
        }
    }

    private void invalidateCaches(long templateId) {
        try {
            cachedTemplateReader.invalidate(templateId);
        } catch (RuntimeException e) {
            log.warn("reshard template cache invalidate failed: templateId={}", templateId, e);
        }
        try {
            redis.delete("c:so:{" + templateId + "}");
        } catch (RuntimeException e) {
            log.warn("reshard sold-out hint clear failed: templateId={}", templateId, e);
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                issueGate.invalidate(templateId);
                return;
            } catch (RuntimeException e) {
                if (attempt == 2) {
                    log.error("reshard gate invalidate failed after retries: templateId={}",
                            templateId, e);
                    return;
                }
                sleep(50L * (attempt + 1));
            }
        }
    }

    // ------------------------------------------------------------------
    // 排空/门禁/幂等重放
    // ------------------------------------------------------------------

    /** 只等 PROCESSING（status=1）；最多 30s。超时继续（正确性由行锁保证，不依赖排空）。 */
    private long drainProcessing(long templateId) {
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        long processing = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                processing = taskMapper.countByTemplateAndStatus(
                        templateId, CouponIssueTaskPO.STATUS_PROCESSING);
            } catch (RuntimeException e) {
                log.warn("reshard drain count failed (continue): templateId={}", templateId, e);
                return -1;
            }
            if (processing == 0) {
                return 0;
            }
            sleep(DRAIN_INTERVAL_MS);
        }
        log.warn("reshard drain timeout: templateId={}, processing={} (continue; row locks arbitrate)",
                templateId, processing);
        return processing;
    }

    private void assertLegacyIndexGone() {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'coupon_stock_shard' "
                + "AND index_name = 'uk_tpl_shard'";
        long present;
        try (Connection connection = rawDataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            present = rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException | RuntimeException e) {
            // 查不到就 fail-closed：不能在无法确认旧键已删除时重排。
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "无法检查旧唯一索引 uk_tpl_shard 是否存在，拒绝 reshard：" + e);
        }
        if (present > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "旧唯一索引 uk_tpl_shard 仍存在：V38 未应用，拒绝 reshard（滚动发布门禁）");
        }
    }

    private ReshardResult replayReshard(CouponStockAdjustLogPO logRow, int targetShardCount) {
        Matcher matcher = RESHARD_REASON.matcher(nullToEmpty(logRow.getReason()));
        if (!matcher.matches()) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "requestId 已被其他库存操作占用，不能用于 reshard");
        }
        int fromVersion = Integer.parseInt(matcher.group(1));
        int toVersion = Integer.parseInt(matcher.group(2));
        int fromShards = Integer.parseInt(matcher.group(3));
        int toShards = Integer.parseInt(matcher.group(4));
        // target 与 effective toShards 不同：remaining 不足时 newN=min(target,remaining)。
        // 幂等比较必须用"请求的目标"，否则 target>remaining 的合法重试会被误判。
        int requestedTarget = Integer.parseInt(matcher.group(5));
        long total = Long.parseLong(matcher.group(6));
        long used = Long.parseLong(matcher.group(7));
        long remaining = Long.parseLong(matcher.group(8));
        if (targetShardCount != requestedTarget) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "requestId 已用于不同的 reshard 目标：已有=" + requestedTarget
                            + ", 本次=" + targetShardCount);
        }
        CouponTemplatePO template = requireTemplate(logRow.getTemplateId());
        if (layoutVersion(template) != toVersion) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "reshard 审计存在但当前版本=" + layoutVersion(template)
                            + "，与审计 toVersion=" + toVersion + " 不一致");
        }
        boolean active = isPublished(template) && shardMapper.countActive(
                logRow.getTemplateId(), toVersion) > 0;
        log.info("stock reshard idempotent replay: templateId={}, requestId={}",
                logRow.getTemplateId(), logRow.getRequestId());
        return new ReshardResult(logRow.getTemplateId(), fromVersion, toVersion,
                fromShards, toShards, total, used, remaining, active, true);
    }

    private RollbackResult replayRollback(CouponStockAdjustLogPO logRow, int expectedNewVersion) {
        Matcher matcher = ROLLBACK_REASON.matcher(nullToEmpty(logRow.getReason()));
        if (!matcher.matches()) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "requestId 已被其他库存操作占用，不能用于 rollback");
        }
        int fromVersion = Integer.parseInt(matcher.group(1));
        int toVersion = Integer.parseInt(matcher.group(2));
        int fromShards = Integer.parseInt(matcher.group(3));
        int toShards = Integer.parseInt(matcher.group(4));
        long total = Long.parseLong(matcher.group(5));
        long used = Long.parseLong(matcher.group(6));
        long remaining = Long.parseLong(matcher.group(7));
        if (fromVersion != expectedNewVersion) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "requestId 已用于不同的 rollback 版本：已有=" + fromVersion
                            + ", 本次=" + expectedNewVersion);
        }
        CouponTemplatePO template = requireTemplate(logRow.getTemplateId());
        if (layoutVersion(template) != toVersion) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "rollback 审计存在但当前版本=" + layoutVersion(template)
                            + "，与审计 toVersion=" + toVersion + " 不一致");
        }
        boolean active = isPublished(template) && shardMapper.countActive(
                logRow.getTemplateId(), toVersion) > 0;
        log.info("stock reshard rollback idempotent replay: templateId={}, requestId={}",
                logRow.getTemplateId(), logRow.getRequestId());
        return new RollbackResult(logRow.getTemplateId(), fromVersion, toVersion,
                fromShards, toShards, total, used, remaining, active, true);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void validateCommon(long templateId, String requestId) {
        if (templateId < 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "templateId 必须为正");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new BizException(ErrorCode.PARAM_ERROR, "requestId 不能为空且不超过 64 字符");
        }
    }

    private void acquireLock(long templateId, String owner) {
        boolean locked;
        try {
            locked = scanLock.tryLock(lockKey(templateId), owner, LOCK_TTL);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.DEGRADED, "库存重排锁不可用，请稍后重试");
        }
        if (!locked) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该模板正在重排/回滚，请稍后重试");
        }
    }

    private void releaseLock(long templateId, String owner) {
        try {
            scanLock.unlock(lockKey(templateId), owner);
        } catch (RuntimeException e) {
            log.warn("stock reshard lock release failed (TTL will expire): templateId={}",
                    templateId, e);
        }
    }

    private static String lockKey(long templateId) {
        return "c:lock:stock-reshard:" + templateId;
    }

    private CouponTemplatePO requireTemplate(long templateId) {
        CouponTemplatePO template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + templateId);
        }
        return template;
    }

    /** 失败处理路径读审计行：DB 抖动不能掩盖原始异常；读不到就按"无并发赢家"处理。 */
    private CouponStockAdjustLogPO findLogQuietly(long templateId, String requestId) {
        try {
            return logMapper.selectByRequest(templateId, requestId);
        } catch (RuntimeException e) {
            log.warn("read adjust log failed during reshard failure handling: templateId={}, requestId={}",
                    templateId, requestId, e);
            return null;
        }
    }

    private static boolean isPublished(CouponTemplatePO template) {
        return template.getPublishStatus() != null && template.getPublishStatus() == 1;
    }

    private static int layoutVersion(CouponTemplatePO template) {
        return template.getStockLayoutVersion() == null ? 1 : template.getStockLayoutVersion();
    }

    private static boolean isReshardLog(CouponStockAdjustLogPO row) {
        return row.getReason() != null && row.getReason().startsWith("RESHARD ");
    }

    private static boolean isRollbackLog(CouponStockAdjustLogPO row) {
        return row.getReason() != null && row.getReason().startsWith("ROLLBACK ");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record ReshardResult(long templateId, int fromVersion, int toVersion,
                                int fromShards, int toShards, long totalCount, long usedCount,
                                long remaining, boolean active, boolean idempotent) {
    }

    public record RollbackResult(long templateId, int fromVersion, int toVersion,
                                 int fromShards, int toShards, long totalCount, long usedCount,
                                 long remaining, boolean active, boolean idempotent) {
    }
}
