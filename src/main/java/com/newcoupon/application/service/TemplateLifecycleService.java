package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 模板冻结/下线/重上线（ADR-0018 §3.5，ADR-0021 Phase 1）。
 *
 * <p>三者的区别：
 * <ul>
 *   <li><b>freeze</b>：只把**当前 layout_version** 的分片 status 置 1，停发；模板仍 published，
 *       已发券不受影响，退役版本不动；</li>
 *   <li><b>offline</b>：publish_status 1→2 + active 分片 status=1；模板不可领取；</li>
 *   <li><b>reonline</b>：publish_status 2→1 + active 分片 status=0；不重置 used，不重建布局。</li>
 * </ul>
 *
 * <p>提交后清本地模板缓存、售罄提示和异步 gate；否则其他实例/下一次受理仍可能读旧状态。
 */
@Service
public class TemplateLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TemplateLifecycleService.class);

    private final TemplateMapper templateMapper;
    private final CouponStockShardMapper shardMapper;
    private final CachedTemplateReader cachedTemplateReader;
    private final StringRedisTemplate redis;
    private final RedisIssueGate issueGate;

    public TemplateLifecycleService(TemplateMapper templateMapper,
                                    CouponStockShardMapper shardMapper,
                                    CachedTemplateReader cachedTemplateReader,
                                    StringRedisTemplate redis,
                                    RedisIssueGate issueGate) {
        this.templateMapper = templateMapper;
        this.shardMapper = shardMapper;
        this.cachedTemplateReader = cachedTemplateReader;
        this.redis = redis;
        this.issueGate = issueGate;
    }

    @Transactional
    public void freeze(long templateId) {
        CouponTemplatePO t = requirePublished(templateId);
        shardMapper.updateStatusAll(templateId, layoutVersion(t), 1);
        afterCommit(templateId);
        log.warn("template frozen: id={}, layoutVersion={}", templateId, layoutVersion(t));
    }

    @Transactional
    public void unfreeze(long templateId) {
        CouponTemplatePO t = requirePublished(templateId);
        shardMapper.updateStatusAll(templateId, layoutVersion(t), 0);
        afterCommit(templateId);
        log.info("template unfrozen: id={}, layoutVersion={}", templateId, layoutVersion(t));
    }

    @Transactional
    public void offline(long templateId) {
        CouponTemplatePO t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + templateId);
        }
        if (t.getPublishStatus() == null || t.getPublishStatus() != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "仅已发布模板可下线");
        }
        if (templateMapper.casUpdatePublishStatus(templateId, 1, 2) != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "模板状态已被并发变更");
        }
        shardMapper.updateStatusAll(templateId, layoutVersion(t), 1);
        afterCommit(templateId);
        log.warn("template offline: id={}, layoutVersion={}", templateId, layoutVersion(t));
    }

    @Transactional
    public void reonline(long templateId) {
        CouponTemplatePO t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + templateId);
        }
        if (t.getPublishStatus() == null || t.getPublishStatus() != 2) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "仅已下线模板可重上线");
        }
        if (templateMapper.casUpdatePublishStatus(templateId, 2, 1) != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "模板状态已被并发变更");
        }
        shardMapper.updateStatusAll(templateId, layoutVersion(t), 0);
        afterCommit(templateId);
        log.info("template reonline: id={}, layoutVersion={}", templateId, layoutVersion(t));
    }

    private CouponTemplatePO requirePublished(long templateId) {
        CouponTemplatePO t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "id=" + templateId);
        }
        if (t.getPublishStatus() == null || t.getPublishStatus() != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "仅已发布模板可冻结");
        }
        return t;
    }

    /** V37：生命周期只动 active version；退役版本保持 status=1、quota=used。 */
    private static int layoutVersion(CouponTemplatePO t) {
        return t.getStockLayoutVersion() == null ? 1 : t.getStockLayoutVersion();
    }

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

    private void afterCommit(long templateId) {
        Runnable action = () -> {
            cachedTemplateReader.invalidate(templateId);
            try {
                redis.delete("c:so:{" + templateId + "}");
            } catch (RuntimeException e) {
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
}
