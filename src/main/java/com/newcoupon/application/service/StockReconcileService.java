package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 库存对账与重建（ADR-0006 第 5 条）——"Redis 是算子，DB 是账本"的守夜人。
 *
 * <p>为什么要它：Redis 不参与数据库事务。正常失败走补偿脚本，但进程被 kill -9、
 * Redis 主从切换丢写等极端场景会留下漂移（Redis 余量 ≠ total - issued）。
 * 对账任务每小时核一次：漂移→告警日志+以 DB 为准重建。**宁可少卖，不可超卖**
 * ——重建值来自 DB 账本，超发被 DB CAS 双防线挡住，重建偏大最多导致少发、对账再收口。
 */
@Service
public class StockReconcileService {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileService.class);

    private final TemplateRepository templateRepository;
    private final RedisStockGateway stockGateway;

    public StockReconcileService(TemplateRepository templateRepository,
                                 RedisStockGateway stockGateway) {
        this.templateRepository = templateRepository;
        this.stockGateway = stockGateway;
    }

    /** 重建单个模板的 Redis 额度：可扣 = 审批总量 - DB 已发放 */
    public void rebuildStock(long templateId) {
        CouponTemplate t = templateRepository.findById(templateId).orElse(null);
        if (t == null) {
            return;
        }
        long expected = t.getTotalCount() - t.getIssuedCount();
        stockGateway.initStock(templateId, expected);
        log.info("redis stock rebuilt: templateId={}, amount={}", templateId, expected);
    }

    /** 每小时对账：漂移（含 key 丢失）→ 告警 + 重建 */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 3600_000)
    public void reconcileAll() {
        List<CouponTemplate> published = templateRepository.findPublished();
        int drift = 0;
        for (CouponTemplate t : published) {
            Long remaining = stockGateway.remaining(t.getId());
            long expected = t.getTotalCount() - t.getIssuedCount();
            if (remaining == null || remaining != expected) {
                drift++;
                log.warn("STOCK DRIFT: templateId={}, redis={}, expected={} → rebuild",
                        t.getId(), remaining, expected);
                rebuildStock(t.getId());
            }
        }
        if (drift == 0) {
            log.info("stock reconcile ok: {} published templates consistent", published.size());
        }
    }
}
