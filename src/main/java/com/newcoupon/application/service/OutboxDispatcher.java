package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.persistence.mapper.CouponOutboxMapper;
import com.newcoupon.infrastructure.persistence.po.CouponOutboxPO;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 发件箱投递器（ADR-0011）：轮询 coupon_outbox → 发布 → 标记。
 *
 * <p>调度：双通道（@XxlJob 生产 + @Scheduled 降级，与库存巡检同款纪律）；
 * 多实例并发由 RedisScanLock 仲裁（本通道 1 秒级，tryLock 抢不到直接让路——
 * 下个实例的本轮自然会捡起未投递行，无需等待）。
 *
 * <p>失败语义（消息永不丢的三段）：
 * ① publish 异常 → 退避重试（2^retry 秒：1s,2s,4s…最多 2^7=128s）；
 * ② 重试 ≥ maxRetry（默认 8）→ 死信 status=2 + 漂移表告警（人工介入）；
 * ③ 死信行仍在表里——修复传输后可人工重置 status=0 续投，或 SQL 取 payload 补发。
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final String LOCK_KEY = "c:lock:outbox-dispatch";
    private static final String TOPIC_ISSUED = "coupon-issued";

    private final CouponOutboxMapper outboxMapper;
    private final EventPublisher publisher;
    private final RedisScanLock scanLock;
    private final StockDriftLogGateway driftLog;
    private final SwitchService switchService;
    private final int batchSize;
    private final int maxRetry;

    private static final String AUDIT_LOCK_KEY = "c:lock:outbox-audit";

    public OutboxDispatcher(CouponOutboxMapper outboxMapper,
                            EventPublisher publisher,
                            RedisScanLock scanLock,
                            StockDriftLogGateway driftLog,
                            SwitchService switchService,
                            @Value("${coupon.outbox.batch-size:100}") int batchSize,
                            @Value("${coupon.outbox.max-retry:8}") int maxRetry) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.scanLock = scanLock;
        this.driftLog = driftLog;
        this.switchService = switchService;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
    }

    /** 双通道入口（1 秒级——发券通知的准实时档；秒杀实时另议，见 ADR-0011 后果节） */
    @com.xxl.job.core.handler.annotation.XxlJob("couponOutboxDispatchJob")
    @Scheduled(fixedDelay = 1_000, initialDelay = 3_000)
    public void dispatch() {
        // 开关关闭 → 本轮直接跳过（连锁都不抢）：消息留在 outbox 里，恢复后照常补投——
        // 这是"暂停投递"而不是"丢弃消息"，两者的差别决定了运维敢不敢按这个开关
        if (!switchService.isOn(SwitchKey.OUTBOX_DISPATCH)) {
            return;
        }
        String owner = "outbox-" + java.util.UUID.randomUUID();
        boolean locked;
        try {
            locked = scanLock.tryLock(LOCK_KEY, owner, Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            log.warn("outbox dispatch: lock acquisition failed (Redis unavailable?) — skip this round", e);
            return;   // 锁都拿不到说明 Redis 抖动——本轮放弃，下一秒再来；绝不炸调度线程
        }
        if (!locked) {
            return;   // 别的实例在投——它本轮会捡完待投行，无需排队
        }
        try {
            int dispatched = 0, retried = 0, dead = 0;
            List<CouponOutboxPO> batch;
            LocalDateTime now = LocalDateTime.now();
            while (!(batch = outboxMapper.selectDispatchable(now, batchSize)).isEmpty()) {
                for (CouponOutboxPO row : batch) {
                    try {
                        publisher.publish(topicOf(row.getEventType()), row.getEventNo(), row.getPayload());
                        outboxMapper.markDispatched(row.getId());
                        dispatched++;
                    } catch (RuntimeException e) {
                        handleFailure(row, e);
                        if (row.getRetryCount() + 1 >= maxRetry) { dead++; } else { retried++; }
                    }
                }
                now = LocalDateTime.now();   // 重退避的行 next_scan_time 已顺延，不会死循环
            }
            if (dispatched + retried + dead > 0) {
                log.info("outbox dispatched: ok={}, retry={}, dead={}", dispatched, retried, dead);
            }
        } catch (RuntimeException e) {
            // 调度通道的铁律：任何异常只记日志——本条让下一轮重扫，永不炸调度线程
            log.warn("outbox dispatch round failed (will rescan next round)", e);
        } finally {
            try {
                scanLock.unlock(LOCK_KEY, owner);
            } catch (RuntimeException ignore) {
                // 解锁失败=锁已过期或 Redis 抖动——锁有 TTL 会自然释放，无需处理
            }
        }
    }

    /**
     * T+1 审计（ADR-0011 承诺的守恒断言；2026-09-30 接上——此前查询方法 {@code issuedEventMinusOutbox}
     * 写好了却**从未被调用**，等于承诺只落了一半）。
     *
     * <p>不变式：发放类事件行数 ≡ 发件箱行数——两者在同一事务里写入，行数不等说明有事务漏写、
     * 或有行被删/被人工改过。差额**只告警不自动修**：修复要人工比对两表差异（正=事件多，负=发件箱多）。
     */
    @com.xxl.job.core.handler.annotation.XxlJob("couponOutboxAuditJob")
    @Scheduled(cron = "${coupon.outbox.audit-cron:0 51 3 * * ?}", zone = "Asia/Shanghai")
    public void outboxAudit() {
        String owner = "outbox-audit-" + java.util.UUID.randomUUID();
        boolean locked;
        try {
            locked = scanLock.tryLock(AUDIT_LOCK_KEY, owner, Duration.ofMinutes(10));
        } catch (RuntimeException e) {
            log.warn("outbox audit: lock acquisition failed — skip this round", e);
            return;
        }
        if (!locked) {
            return;
        }
        try {
            long diff = outboxMapper.issuedEventMinusOutbox();
            if (diff != 0) {
                log.error("OUTBOX AUDIT MISMATCH: 发放事件行数 − 发件箱行数 = {}（应为 0——同事务双写的直接推论被破坏，"
                        + "人工比对两表差异；正=事件多，负=发件箱多）", diff);
                driftLog.record(new StockDriftLogGateway.DriftEvent(0, 0, 0, 0, 0, 0, null, null, 0,
                        "OUTBOX_MISMATCH", "事件行数−发件箱行数=" + diff));
            } else {
                log.info("outbox audit clean: issued events ≡ outbox rows");
            }
        } catch (RuntimeException e) {
            log.warn("outbox audit round failed (will rescan tomorrow)", e);
        } finally {
            try {
                scanLock.unlock(AUDIT_LOCK_KEY, owner);
            } catch (RuntimeException ignore) {
                // 锁有 TTL，自然释放
            }
        }
    }

    /** 失败处理：退避 2^retry 秒；耗尽置死信+漂移表告警（OUTBOX_DEAD——人工介入信号） */
    private void handleFailure(CouponOutboxPO row, RuntimeException e) {
        String err = truncate(e.getMessage());
        int nextRetry = row.getRetryCount() + 1;
        if (nextRetry >= maxRetry) {
            outboxMapper.markDead(row.getId(), err);
            log.error("OUTBOX DEAD: eventNo={} retry={} lastError={} → 人工介入（行保留，修复传输后可重置续投）",
                    row.getEventNo(), nextRetry, err);
            driftLog.record(new StockDriftLogGateway.DriftEvent(0L, 0, 0, 0,
                    0, 0, null, null, 0, "OUTBOX_DEAD",
                    "发件箱投递重试耗尽: eventNo=" + row.getEventNo() + ", retry=" + nextRetry
                            + ", lastError=" + err, null, null));
            return;
        }
        long backoffSeconds = 1L << Math.min(nextRetry, 7);   // 2^retry，封顶 128s
        outboxMapper.markRetry(row.getId(), LocalDateTime.now().plusSeconds(backoffSeconds), err);
        log.warn("outbox retry: eventNo={} retry={} backoff={}s error={}",
                row.getEventNo(), nextRetry, backoffSeconds, err);
    }

    private String topicOf(int eventType) {
        return eventType == 1 ? TOPIC_ISSUED : "coupon-event-" + eventType;
    }

    private String truncate(String s) {
        if (s == null) { return "unknown"; }
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
