package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C1 分片布局的建立与校验（ADR-0018 §3.2）。
 *
 * <p>发布时一次性建 N_eff 行，Σquota=total_count；行只建一次，之后调整库存只改 quota，
 * 不重开布局、不建世代。这个方法必须和发布状态 CAS 在同一个事务里：
 * 否则可能出现“模板已发布、分片行没建”的中间态，用户领券只能拿到降级错误。
 */
@Service
public class StockShardLayoutService {

    private static final Logger log = LoggerFactory.getLogger(StockShardLayoutService.class);

    private final TemplateMapper templateMapper;
    private final CouponStockShardMapper shardMapper;

    public StockShardLayoutService(TemplateMapper templateMapper,
                                   CouponStockShardMapper shardMapper) {
        this.templateMapper = templateMapper;
        this.shardMapper = shardMapper;
    }

    /**
     * 为 C1 模板建立分片布局。幂等：若已经存在且布局一致，直接返回（发布事务重试/恢复时安全）。
     *
     * @throws IllegalStateException 已存在行但布局与 total/N 不一致；由发布事务整体回滚，不带着坏布局上线
     */
    @Transactional
    public void initializeForPublish(long templateId) {
        CouponTemplatePO t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new IllegalStateException("C1 layout: template not found, id=" + templateId);
        }
        int configured = t.getStockShardCount() == null ? 16 : t.getStockShardCount();
        int layoutVersion = t.getStockLayoutVersion() == null ? 1 : t.getStockLayoutVersion();
        long total = t.getTotalCount();
        int effective = StockShardPlan.effectiveShards(configured, total);

        List<CouponStockShardPO> existing = shardMapper.selectByTemplateVersion(templateId, layoutVersion);
        if (!existing.isEmpty()) {
            assertLayout(existing, effective, total, templateId, layoutVersion);
            templateMapper.updateActualShardCount(templateId, effective);
            return;
        }

        long[] quotas = StockShardPlan.quotas(effective, total);
        for (int shardNo = 0; shardNo < effective; shardNo++) {
            CouponStockShardPO row = new CouponStockShardPO();
            row.setTemplateId(templateId);
            row.setLayoutVersion(layoutVersion);
            row.setShardNo(shardNo);
            row.setQuota(quotas[shardNo]);
            row.setUsed(0L);
            row.setReserved(0L);
            row.setStatus(0);
            row.setVersion(0L);
            shardMapper.insert(row);
        }
        long sum = shardMapper.sumQuotaVersion(templateId, layoutVersion);
        if (sum != total) {
            throw new IllegalStateException("C1 layout assertion failed: Σquota=" + sum
                    + " != total=" + total + ", templateId=" + templateId
                    + ", layoutVersion=" + layoutVersion);
        }
        // 把“配置片数”落成实际 N_eff：领券路由直接按这个值探测，
        // 避免 total 很小（例如 2）时每次白探 6 个不存在的片。
        templateMapper.updateActualShardCount(templateId, effective);
        log.info("C1 stock layout initialized: templateId={}, layoutVersion={}, "
                        + "configuredShards={}, effectiveShards={}, total={}",
                templateId, layoutVersion, configured, effective, total);
    }

    private void assertLayout(List<CouponStockShardPO> rows, int effective, long total,
                              long templateId, int layoutVersion) {
        long sum = rows.stream().mapToLong(r -> r.getQuota() == null ? 0 : r.getQuota()).sum();
        boolean sameCount = rows.size() == effective;
        boolean sameTotal = sum == total;
        boolean sameVersion = rows.stream().allMatch(r -> Integer.valueOf(layoutVersion)
                .equals(r.getLayoutVersion()));
        boolean validUsed = rows.stream().allMatch(r -> r.getUsed() != null
                && r.getQuota() != null && r.getUsed() >= 0 && r.getUsed() <= r.getQuota());
        if (!sameCount || !sameTotal || !sameVersion || !validUsed) {
            throw new IllegalStateException("C1 layout assertion failed: templateId=" + templateId
                    + ", layoutVersion=" + layoutVersion
                    + ", rows=" + rows.size() + " (expected " + effective + ")"
                    + ", Σquota=" + sum + " (expected " + total + ")"
                    + ", versionConsistent=" + sameVersion
                    + ", usedRangeValid=" + validUsed);
        }
    }
}
