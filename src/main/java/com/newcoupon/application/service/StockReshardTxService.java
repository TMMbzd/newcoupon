package com.newcoupon.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockAdjustLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponStockAdjustLogPO;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * reshard / rollback 的**事务内**部分（编排、锁、排空在 {@link StockReshardService}）。
 *
 * <p>为什么拆成单独 Bean：`@Transactional` 是代理语义，编排里的“冻结→提交→排空→
 * 再开事务”必须跨越多个事务，不能在一个大事务里 self-invocation 调用事务方法。
 *
 * <p>事务内顺序（reshard）：锁 active 行 → 两段式 U==COUNT(user_coupon) →
 * 计算 remaining/newN → CAS 模板版本/片数 → 退役旧行 quota=used → 插入新行 →
 * 断言全局 Σquota/Σused → 写审计。任一步失败整体回滚，旧版本保持冻结，由编排层恢复。
 */
@Service
public class StockReshardTxService {

    private static final Logger log = LoggerFactory.getLogger(StockReshardTxService.class);
    private static final long USED_COUNT_RECHECK_DELAY_MS = 200;

    private final TemplateMapper templateMapper;
    private final CouponStockShardMapper shardMapper;
    private final CouponStockAdjustLogMapper logMapper;
    private final UserCouponRepository userCouponRepository;
    private final ObjectMapper objectMapper;

    public StockReshardTxService(TemplateMapper templateMapper,
                                 CouponStockShardMapper shardMapper,
                                 CouponStockAdjustLogMapper logMapper,
                                 UserCouponRepository userCouponRepository,
                                 ObjectMapper objectMapper) {
        this.templateMapper = templateMapper;
        this.shardMapper = shardMapper;
        this.logMapper = logMapper;
        this.userCouponRepository = userCouponRepository;
        this.objectMapper = objectMapper;
    }

    /** 单语句状态切换，单独事务提交：冻结/解冻必须让其他实例的 worker 立刻看到。 */
    @Transactional
    public void setStatus(long templateId, int layoutVersion, int status) {
        shardMapper.updateStatusAll(templateId, layoutVersion, status);
    }

    /**
     * 正向 reshard 事务。调用前 active version 必须已冻结并完成软排空。
     */
    @Transactional
    public ReshardOutcome executeReshard(long templateId, int targetShardCount,
                                         String requestId, String operator) {
        // 先锁模板行，再锁分片行：与 StockShardAdjustService 的加锁顺序一致，避免死锁。
        CouponTemplatePO template = requireTemplateForUpdate(templateId);
        requirePublished(template);
        int fromVersion = layoutVersion(template);
        int oldShards = Math.max(1, template.getStockShardCount() == null
                ? 1 : template.getStockShardCount());
        long total = nz(template.getTotalCount());

        // 锁 active 行：等待在途本地事务提交/回滚后再读最终 used；PENDING 不等待。
        List<CouponStockShardPO> oldRows =
                shardMapper.selectByTemplateVersionForUpdate(templateId, fromVersion);
        if (oldRows.isEmpty()) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "active 布局不存在，不能重排：version=" + fromVersion);
        }
        if (oldRows.size() != oldShards) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "active 布局行数与片数不一致：rows=" + oldRows.size() + ", count=" + oldShards);
        }

        long quota = shardMapper.sumQuota(templateId);
        if (quota != total) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "全局 Σquota=" + quota + " != total=" + total + "，拒绝重排");
        }
        long used = twoPhaseUsed(templateId, "reshard");
        long remaining = quota - used;
        if (remaining <= 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "剩余额度为 0，无需也无法重排：total=" + total + ", used=" + used);
        }
        int newShards = (int) Math.min((long) targetShardCount, remaining);
        if (newShards < 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "目标片数无效：" + targetShardCount);
        }
        long[] quotas = StockShardPlan.quotas(newShards, remaining);
        int toVersion = fromVersion + 1;

        // 模板 CAS 是并发闸门：并发调整/另一次 reshard 先提交则 0 行，本事务整体回滚。
        if (templateMapper.casBumpLayoutVersion(
                templateId, fromVersion, toVersion, newShards, total) != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "布局版本已被并发变更，reshard 取消");
        }
        int retired = shardMapper.retireVersion(templateId, fromVersion);
        if (retired != oldShards) {
            throw new IllegalStateException("retire rows mismatch: expected=" + oldShards
                    + ", actual=" + retired + ", templateId=" + templateId);
        }
        for (int shardNo = 0; shardNo < newShards; shardNo++) {
            CouponStockShardPO row = new CouponStockShardPO();
            row.setTemplateId(templateId);
            row.setLayoutVersion(toVersion);
            row.setShardNo(shardNo);
            row.setQuota(quotas[shardNo]);
            row.setUsed(0L);
            row.setReserved(0L);
            row.setStatus(0);
            row.setVersion(0L);
            shardMapper.insert(row);
        }

        assertResharded(templateId, fromVersion, toVersion, newShards, total, used);
        writeAudit(templateId, requestId, operator, total, used, remaining, oldRows,
                shardMapper.selectByTemplateVersion(templateId, toVersion),
                String.format(Locale.ROOT,
                        "RESHARD v%d->v%d shards=%d->%d target=%d total=%d used=%d remaining=%d",
                        fromVersion, toVersion, oldShards, newShards,
                        targetShardCount, total, used, remaining));
        log.info("stock reshard committed: templateId={}, v{}->v{}, shards {}->{}, total={}, used={}",
                templateId, fromVersion, toVersion, oldShards, newShards, total, used);
        return new ReshardOutcome(fromVersion, toVersion, oldShards, newShards,
                total, used, remaining);
    }

    /**
     * 反向 rollback 事务。调用前 new version 必须已冻结。
     * 只允许新版本 Σused=0；已出券后只能 forward-fix。
     */
    @Transactional
    public RollbackOutcome executeRollback(long templateId, int expectedNewVersion,
                                           String requestId, String operator) {
        CouponTemplatePO template = requireTemplateForUpdate(templateId);
        int newVersion = layoutVersion(template);
        if (newVersion != expectedNewVersion) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "布局版本已变化：expected=" + expectedNewVersion + ", actual=" + newVersion);
        }
        int oldVersion = expectedNewVersion - 1;
        if (oldVersion < 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "没有可回滚的历史版本");
        }
        List<CouponStockShardPO> newRows =
                shardMapper.selectByTemplateVersionForUpdate(templateId, newVersion);
        if (newRows.isEmpty()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "新版本布局不存在，不能回滚");
        }
        long newUsed = shardMapper.sumUsedVersion(templateId, newVersion);
        if (newUsed != 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "新版本已发出 " + newUsed + " 张券，拒绝回滚（只能 forward-fix）");
        }
        long remaining = shardMapper.sumQuotaVersion(templateId, newVersion);
        List<CouponStockShardPO> oldRows =
                shardMapper.selectByTemplateVersionForUpdate(templateId, oldVersion);
        if (oldRows.isEmpty()) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "历史版本 v" + oldVersion + " 不存在，不能回滚");
        }
        boolean retiredBalanced = oldRows.stream().allMatch(r ->
                Integer.valueOf(1).equals(r.getStatus()) && nz(r.getQuota()) == nz(r.getUsed()));
        if (!retiredBalanced) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "历史版本 v" + oldVersion + " 不是合法退役布局（status=1, quota=used），拒绝回滚");
        }
        long total = nz(template.getTotalCount());
        long quota = shardMapper.sumQuota(templateId);
        long used = shardMapper.sumUsed(templateId);
        if (quota != total) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "全局 Σquota=" + quota + " != total=" + total + "，拒绝回滚");
        }

        int oldShards = oldRows.size();
        if (templateMapper.casBumpLayoutVersion(
                templateId, newVersion, oldVersion, oldShards, total) != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "布局版本已被并发变更，回滚取消");
        }
        int deleted = shardMapper.deleteUnusedVersion(templateId, newVersion);
        if (deleted != newRows.size()) {
            // SQL 自带 used=0 守卫；行数不一致说明新版本在锁内被改动，整体回滚。
            throw new IllegalStateException("delete new-version rows mismatch: expected="
                    + newRows.size() + ", actual=" + deleted);
        }

        // 把新版本剩余额度按行均摊回旧版本（旧行当前 quota=used）。
        long base = remaining / oldShards;
        long extra = remaining % oldShards;
        for (int i = 0; i < oldShards; i++) {
            long add = base + (i < extra ? 1 : 0);
            if (add == 0) {
                continue;
            }
            int updated = shardMapper.casAddQuota(
                    templateId, oldVersion, oldRows.get(i).getShardNo(), add);
            if (updated != 1) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "恢复历史分片额度失败，回滚取消");
            }
        }
        int restoredStatus = template.getPublishStatus() != null
                && template.getPublishStatus() == 1 ? 0 : 1;
        shardMapper.updateStatusAll(templateId, oldVersion, restoredStatus);

        assertRolledBack(templateId, newVersion, oldVersion, total, used);
        writeAudit(templateId, requestId, operator, total, used, remaining, newRows,
                shardMapper.selectByTemplateVersion(templateId, oldVersion),
                String.format(Locale.ROOT,
                        "ROLLBACK v%d->v%d shards=%d->%d total=%d used=%d remaining=%d",
                        newVersion, oldVersion, newRows.size(), oldShards, total, used, remaining));
        log.warn("stock reshard rolled back: templateId={}, v{}->v{}, shards {}->{}",
                templateId, newVersion, oldVersion, newRows.size(), oldShards);
        return new RollbackOutcome(newVersion, oldVersion, newRows.size(), oldShards,
                total, used, remaining);
    }

    // ------------------------------------------------------------------

    private long twoPhaseUsed(long templateId, String operation) {
        long used = shardMapper.sumUsed(templateId);
        long coupons = userCouponRepository.countByTemplate(templateId);
        if (used == coupons) {
            return used;
        }
        // 设计契约：首次不一致短延迟重读；仍不一致 abort，绝不带着未知误差搬库存。
        try {
            Thread.sleep(USED_COUNT_RECHECK_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, operation + " 被中断");
        }
        used = shardMapper.sumUsed(templateId);
        coupons = userCouponRepository.countByTemplate(templateId);
        if (used != coupons) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "库存与券数不一致，取消 " + operation
                            + "：Σused=" + used + ", COUNT(user_coupon)=" + coupons);
        }
        return used;
    }

    private void assertResharded(long templateId, int fromVersion, int toVersion,
                                 int newShards, long total, long used) {
        long quota = shardMapper.sumQuota(templateId);
        long currentUsed = shardMapper.sumUsed(templateId);
        long newRows = shardMapper.countByTemplateVersion(templateId, toVersion);
        long active = shardMapper.countActive(templateId, toVersion);
        List<CouponStockShardPO> retired = shardMapper.selectByTemplateVersion(templateId, fromVersion);
        boolean retiredBalanced = retired.stream().allMatch(r ->
                Integer.valueOf(1).equals(r.getStatus()) && nz(r.getQuota()) == nz(r.getUsed()));
        if (quota != total || currentUsed != used || newRows != newShards
                || active != newShards || !retiredBalanced) {
            throw new IllegalStateException("reshard assertion failed: templateId=" + templateId
                    + ", Σquota=" + quota + "(expect " + total + ")"
                    + ", Σused=" + currentUsed + "(expect " + used + ")"
                    + ", newRows=" + newRows + "(expect " + newShards + ")"
                    + ", active=" + active + ", retiredBalanced=" + retiredBalanced);
        }
    }

    private void assertRolledBack(long templateId, int removedVersion, int restoredVersion,
                                  long total, long used) {
        long quota = shardMapper.sumQuota(templateId);
        long currentUsed = shardMapper.sumUsed(templateId);
        long removed = shardMapper.countByTemplateVersion(templateId, removedVersion);
        long restoredRows = shardMapper.countByTemplateVersion(templateId, restoredVersion);
        if (quota != total || currentUsed != used || removed != 0 || restoredRows < 1) {
            throw new IllegalStateException("rollback assertion failed: templateId=" + templateId
                    + ", Σquota=" + quota + "(expect " + total + ")"
                    + ", Σused=" + currentUsed + "(expect " + used + ")"
                    + ", removedRows=" + removed + ", restoredRows=" + restoredRows);
        }
    }

    private void writeAudit(long templateId, String requestId, String operator,
                            long total, long used, long remaining,
                            List<CouponStockShardPO> before,
                            List<CouponStockShardPO> after, String reason) {
        CouponStockAdjustLogPO audit = new CouponStockAdjustLogPO();
        audit.setTemplateId(templateId);
        audit.setRequestId(requestId);
        audit.setOperator(operator == null || operator.isBlank() ? "admin" : operator);
        audit.setReason(reason);
        audit.setExpectedTotal(total);
        audit.setBeforeTotal(total);
        audit.setTargetTotal(total);
        audit.setBeforeShardsJson(toJson(before));
        audit.setAfterShardsJson(toJson(after));
        // uk_tpl_request 终审并发同 requestId：DuplicateKey 由编排层转幂等重放。
        logMapper.insert(audit);
    }

    private CouponTemplatePO requireTemplateForUpdate(long templateId) {
        CouponTemplatePO template = templateMapper.selectByIdForUpdate(templateId);
        if (template == null) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + templateId);
        }
        return template;
    }

    private static void requirePublished(CouponTemplatePO template) {
        if (template.getPublishStatus() == null || template.getPublishStatus() != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "仅已发布模板可重排");
        }
    }

    private static int layoutVersion(CouponTemplatePO template) {
        return template.getStockLayoutVersion() == null ? 1 : template.getStockLayoutVersion();
    }

    private String toJson(List<CouponStockShardPO> rows) {
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (CouponStockShardPO row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("layoutVersion", row.getLayoutVersion());
            map.put("shardNo", row.getShardNo());
            map.put("quota", row.getQuota());
            map.put("used", row.getUsed());
            map.put("reserved", row.getReserved());
            map.put("status", row.getStatus());
            list.add(map);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return list.toString();
        }
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    public record ReshardOutcome(int fromVersion, int toVersion, int fromShards, int toShards,
                                 long totalCount, long usedCount, long remaining) {
    }

    public record RollbackOutcome(int fromVersion, int toVersion, int fromShards, int toShards,
                                  long totalCount, long usedCount, long remaining) {
    }
}
