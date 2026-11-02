package com.newcoupon.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.application.dto.StockAdjustRequest;
import com.newcoupon.application.dto.StockAdjustResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockAdjustLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponStockAdjustLogPO;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * C1 库存调整（ADR-0018 §3.6；V37 起只调整 active layout_version）——只改 quota，
 * 不重建布局、不建世代；退役版本保持只读（quota=used）。
 *
 * <p>规则：
 * <ul>
 *   <li>目标总量不得低于 {@code SUM(used)}：已发券不能被减库存抹掉；</li>
 *   <li>增加：把 delta 均摊到现有分片的 quota；</li>
 *   <li>减少：只从 {@code quota-used} 足够的片里减，单条 SQL 带 {@code quota-used>=delta} 守卫；</li>
 *   <li>requestId 幂等，审计记录调整前后总量和各片明细；</li>
 *   <li>提交后清售罄提示并失效模板缓存——否则调整后最多 10 秒仍报售罄/旧总量。</li>
 * </ul>
 */
@Service
public class StockShardAdjustService {

    private static final Logger log = LoggerFactory.getLogger(StockShardAdjustService.class);

    private final TemplateMapper templateMapper;
    private final CouponStockShardMapper shardMapper;
    private final CouponStockAdjustLogMapper logMapper;
    private final CachedTemplateReader cachedTemplateReader;
    private final StringRedisTemplate redis;
    private final RedisIssueGate issueGate;
    private final ObjectMapper objectMapper;

    public StockShardAdjustService(TemplateMapper templateMapper,
                                   CouponStockShardMapper shardMapper,
                                   CouponStockAdjustLogMapper logMapper,
                                   CachedTemplateReader cachedTemplateReader,
                                   StringRedisTemplate redis,
                                   RedisIssueGate issueGate,
                                   ObjectMapper objectMapper) {
        this.templateMapper = templateMapper;
        this.shardMapper = shardMapper;
        this.logMapper = logMapper;
        this.cachedTemplateReader = cachedTemplateReader;
        this.redis = redis;
        this.issueGate = issueGate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StockAdjustResult adjust(long templateId, StockAdjustRequest req) {
        long expected = req.expectedTotalCount();
        long target = req.targetTotalCount();
        if (target < 0 || expected < 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "总量不能为负");
        }

        // 幂等：同一 requestId 已执行过，直接返回当时的目标总量，不再改额度
        CouponStockAdjustLogPO existing = logMapper.selectByRequest(templateId, req.requestId());
        if (existing != null) {
            // 同一张审计表被 adjust/reshard/rollback 共用；重排日志的 target_total 也是 total，
            // 若不做前缀区分，adjust 复用同一个 requestId 会被误当成"幂等成功"而什么都不做。
            String reason = existing.getReason();
            if (reason != null && (reason.startsWith("RESHARD ") || reason.startsWith("ROLLBACK "))) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "requestId 已被库存重排/回滚占用，不能用于库存调整");
            }
            if (existing.getTargetTotal() != target) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "requestId 已用于不同的库存调整目标");
            }
            return new StockAdjustResult(templateId, existing.getTargetTotal(),
                    shardMapper.sumUsed(templateId), true);
        }

        CouponTemplatePO t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + templateId);
        }
        long current = t.getTotalCount() == null ? 0 : t.getTotalCount();
        if (current != expected) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "总量已被并发调整：当前=" + current + "，请求预期=" + expected);
        }
        int layoutVersion = t.getStockLayoutVersion() == null ? 1 : t.getStockLayoutVersion();

        // 只调整 active version；Σquota/Σused 用全局口径（退役行 quota=used，仍计入总量/已发）。
        List<CouponStockShardPO> before = shardMapper.selectByTemplateVersion(templateId, layoutVersion);
        if (before.isEmpty()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "库存布局未初始化，不能调整");
        }
        long sumQuota = shardMapper.sumQuota(templateId);
        long sumUsed = shardMapper.sumUsed(templateId);
        if (sumQuota != current) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "库存布局不守恒：Σquota=" + sumQuota + "，total=" + current
                            + "，layoutVersion=" + layoutVersion);
        }
        if (target < sumUsed) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "目标总量不能低于已发放量：target=" + target + "，SUM(used)=" + sumUsed);
        }

        // 先 CAS 总量，拿到模板行的并发闸门；随后的分片更新仍在同一事务，失败会一起回滚
        if (templateMapper.casUpdateTotalCount(templateId, current, target) != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "总量已被并发调整，请刷新后重试");
        }

        if (target > current) {
            increase(templateId, layoutVersion, before, target - current);
        } else if (target < current) {
            decrease(templateId, layoutVersion, before, current - target);
        }

        long afterQuota = shardMapper.sumQuota(templateId);
        if (afterQuota != target) {
            throw new IllegalStateException("C1 stock adjust assertion failed: Σquota="
                    + afterQuota + " != target=" + target + ", templateId=" + templateId
                    + ", layoutVersion=" + layoutVersion);
        }

        CouponStockAdjustLogPO audit = new CouponStockAdjustLogPO();
        audit.setTemplateId(templateId);
        audit.setRequestId(req.requestId());
        audit.setOperator(req.operator() == null || req.operator().isBlank() ? "admin" : req.operator());
        audit.setReason(req.reason());
        audit.setExpectedTotal(expected);
        audit.setBeforeTotal(current);
        audit.setTargetTotal(target);
        audit.setBeforeShardsJson(toJson(before));
        audit.setAfterShardsJson(toJson(shardMapper.selectByTemplateVersion(templateId, layoutVersion)));
        logMapper.insert(audit);

        afterCommit(templateId);
        log.info("C1 stock adjusted: templateId={}, layoutVersion={}, total={}->{}, used={}, requestId={}",
                templateId, layoutVersion, current, target, sumUsed, req.requestId());
        return new StockAdjustResult(templateId, target, sumUsed, false);
    }

    /** 增加：delta 均摊到现有分片；这是管理操作，N 最多几十，逐片 UPDATE 足够。 */
    private void increase(long templateId, int layoutVersion,
                          List<CouponStockShardPO> shards, long delta) {
        int n = shards.size();
        long base = delta / n;
        long remainder = delta % n;
        for (int i = 0; i < n; i++) {
            long add = base + (i < remainder ? 1 : 0);
            if (add == 0) {
                continue;
            }
            int updated = shardMapper.casAddQuota(
                    templateId, layoutVersion, shards.get(i).getShardNo(), add);
            if (updated != 1) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "分片额度更新失败，请重试");
            }
        }
    }

    /**
     * 减少：优先从“未发额度最多”的片减，保证每片 quota>=used。
     * 任何一片在读取后被并发领券用掉更多，CAS 守卫返回 0 行，整笔调整回滚。
     */
    private void decrease(long templateId, int layoutVersion,
                          List<CouponStockShardPO> shards, long delta) {
        List<CouponStockShardPO> ordered = new ArrayList<>(shards);
        ordered.sort(Comparator.comparingLong(
                (CouponStockShardPO r) -> nz(r.getQuota()) - nz(r.getUsed())).reversed());

        long remaining = delta;
        for (CouponStockShardPO row : ordered) {
            if (remaining == 0) {
                break;
            }
            long available = nz(row.getQuota()) - nz(row.getUsed());
            long take = Math.min(remaining, available);
            if (take == 0) {
                continue;
            }
            int updated = shardMapper.casReduceQuota(
                    templateId, layoutVersion, row.getShardNo(), take);
            if (updated != 1) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "库存正在变动（或有片额度不足），请稍后重试");
            }
            remaining -= take;
        }
        if (remaining > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "未发额度不足，无法减到目标总量");
        }
    }

    private void afterCommit(long templateId) {
        Runnable action = () -> {
            cachedTemplateReader.invalidate(templateId);
            try {
                redis.delete("c:so:{" + templateId + "}");
            } catch (RuntimeException e) {
                // 提示删不掉最多 10 秒后自然过期；不能因此回滚已提交的调整
                log.debug("clear sold-out hint failed: templateId={}", templateId, e);
            }
            invalidateGateWithRetry(templateId);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 库存调整已提交，gate 删除失败不能回滚；短重试 + 日志，仍失败靠 TTL/重建。 */
    private void invalidateGateWithRetry(long templateId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                issueGate.invalidate(templateId);
                return;
            } catch (RuntimeException e) {
                if (attempt == 2) {
                    log.error("invalidate issue gate failed after retries: templateId={}",
                            templateId, e);
                    return;
                }
                try {
                    Thread.sleep(50L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private String toJson(List<CouponStockShardPO> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CouponStockShardPO r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("layoutVersion", r.getLayoutVersion());
            m.put("shardNo", r.getShardNo());
            m.put("quota", r.getQuota());
            m.put("used", r.getUsed());
            m.put("reserved", r.getReserved());
            m.put("status", r.getStatus());
            list.add(m);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return list.toString();
        }
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
