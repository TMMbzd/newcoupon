package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.infrastructure.persistence.CouponReleaseTaskGateway;
import com.newcoupon.infrastructure.persistence.po.CouponReleaseTaskPO;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 锁定超时执行器与兜底扫描（L5，2026-09-15，docs/L5 技术方案）。
 *
 * <p>问题（静态穷举证明，方案 §2）：券 LOCKED 的三个出口——核销/释放都要"有人来调"
 * （订单侧消息丢失时没人调）、锁定中过期要"时间到"（券可能还有半年有效期）——
 * 三出口同时关闭=券永久悬挂。本服务给 LOCKED 补第四个出口：到点收场。
 *
 * <p>判定纪律（方案 D2 补记）：判断的不是"订单死没死"（沉默不可知），而是"契约窗口内
 * 办完没"——时间守卫（deadline 固化在任务行，不到点扫描器物理不可见）+
 * 状态守卫（收场 CAS 的 WHERE status=0：订单窗口内说过话则影响 0 行 no-op）。
 *
 * <p>执行纪律与 CouponExpireService 同骨架：每批 ≤500、单轮 ≤20 批；失败=退避推进
 * next_scan_time（不设失败状态，扫描条件永远单值）；重试满 8 次死信等人工；
 * 正确性由 timeoutClose 事务内的 CAS 保证，调度只是提醒者。
 */
@Service
public class CouponReleaseService {

    private static final Logger log = LoggerFactory.getLogger(CouponReleaseService.class);
    private static final String SCAN_LOCK_KEY = "c:rel:scanlock:0";   // 0=当前单分片，分片广播时按 shard 扩键
    private static final String AUDIT_LOCK_KEY = "c:rel:auditlock";

    private final TradeTxService tradeTxService;
    private final CouponUsageRepository couponUsageRepository;
    private final CouponReleaseTaskGateway taskGateway;
    private final RedisScanLock scanLock;
    private final long lockTimeoutMinutes;

    public CouponReleaseService(TradeTxService tradeTxService,
                                CouponUsageRepository couponUsageRepository,
                                CouponReleaseTaskGateway taskGateway,
                                RedisScanLock scanLock,
                                @Value("${coupon.trade.lock-timeout-minutes:30}") long lockTimeoutMinutes) {
        this.tradeTxService = tradeTxService;
        this.couponUsageRepository = couponUsageRepository;
        this.taskGateway = taskGateway;
        this.scanLock = scanLock;
        this.lockTimeoutMinutes = lockTimeoutMinutes;
    }

    /** 兜底扫描：每分钟捞到期待办小批量处理。异常只记日志下一轮重试（调度不中断）。
     *  多实例互斥：Redis 锁抢轮次——正确性本由幂等 CAS 保证，锁消的是重复扫描负载。 */
    @XxlJob("couponReleaseScanJob")   // XXL-JOB 通道（ADR-0010，路由策略=分片广播）；@Scheduled 为降级通道，锁仲裁
    @Scheduled(fixedDelay = 60_000, initialDelay = 180_000)
    public int scan() {
        String owner = "rel-scan-" + java.util.UUID.randomUUID();
        if (!scanLock.tryLock(SCAN_LOCK_KEY, owner, java.time.Duration.ofSeconds(90))) {
            log.debug("release scan skipped: another instance holds the lock");
            return 0;
        }
        try {
            int[] shard = shardParams();   // 分片广播：每个实例只扫自己的分片；降级通道=单片全扫
            int processed = scanOnce(shard[0], shard[1]);
            if (processed > 0) {
                log.info("release scan: {} tasks processed", processed);
            }
            return processed;
        } catch (Exception e) {
            log.error("release scan round failed (next round retries)", e);
            return -1;
        } finally {
            scanLock.unlock(SCAN_LOCK_KEY, owner);
        }
    }

    /** 分片参数：XXL-JOB 分片广播时取触发上下文的分片号/总数；@Scheduled 降级通道=0/1 全扫 */
    private int[] shardParams() {
        try {
            int total = XxlJobHelper.getShardTotal();
            int index = XxlJobHelper.getShardIndex();
            if (total > 0 && index >= 0 && index < total) {
                return new int[]{index, total};
            }
        } catch (Exception ignore) {
            // 非 XXL-JOB 触发线程（@Scheduled/测试/手动）：上下文不存在——单片全扫
        }
        return new int[]{0, 1};
    }

    /**
     * 单轮扫描（测试与人工触发可直接调）：最多 20 批 × 每批 500 条。
     *
     * @return 本轮处理的任务数
     */
    public int scanOnce(int shardIndex, int shardTotal) {
        int processed = 0;
        for (int round = 0; round < 20; round++) {
            List<CouponReleaseTaskPO> batch = taskGateway.scanDueShard(shardIndex, shardTotal, 500);
            if (batch.isEmpty()) {
                break;
            }
            for (CouponReleaseTaskPO task : batch) {
                processOne(task.getUsageNo());
                processed++;
            }
        }
        return processed;
    }

    /** 单合同执行：收场或确认无需收场，任何结局任务终结；失败退避（8 次死信等人工） */
    private void processOne(String usageNo) {
        try {
            boolean closed = tradeTxService.timeoutClose(usageNo, LocalDateTime.now());
            if (closed) {
                log.warn("lock timeout closed: usageNo={}（订单沉默超窗口，券回池/过期，占用单→超时关闭）", usageNo);
            }
            taskGateway.finish(usageNo);
        } catch (Exception e) {
            log.error("release task failed → backoff: usageNo={}", usageNo, e);
            taskGateway.backoff(usageNo);
        }
    }

    /**
     * T+1 锁龄审计（L5）：仍占用中但锁龄超"窗口+10分钟"的合同 = 有锁无任务（旁路锁定/漏注册）——
     * 自动补注册（下轮扫描收走）+告警。10 分钟压在正常收敛上界之上（扫描周期 60s + 最坏退避 8.5min）。
     * 查询走占用单侧（V7 idx_occupy：status 等值前缀精确命中——在途占用本就是小集合）。
     */
    @XxlJob("couponReleaseAuditJob")   // 单实例路由（锁内幂等），cron 由调度中心管理
    @Scheduled(cron = "${coupon.release.audit-cron:0 41 4 * * ?}", zone = "Asia/Shanghai")
    public void dailyAudit() {
        String owner = "rel-audit-" + java.util.UUID.randomUUID();
        if (!scanLock.tryLock(AUDIT_LOCK_KEY, owner, java.time.Duration.ofMinutes(30))) {
            log.info("release audit skipped: another instance holds the lock");
            return;
        }
        try {
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(lockTimeoutMinutes + 10);
            List<CouponUsage> leaked = couponUsageRepository.selectOverdueOccupying(threshold, 1000);
            for (CouponUsage usage : leaked) {
                taskGateway.registerIfAbsent(usage.getUsageNo(), usage.getUserCouponId(), usage.getUserId());
            }
            if (!leaked.isEmpty()) {
                log.warn("release audit: {} leaked lock contracts re-registered (旁路锁定？)", leaked.size());
            }
            log.info("release daily audit done");
        } catch (Exception e) {
            log.error("release daily audit failed", e);
        } finally {
            scanLock.unlock(AUDIT_LOCK_KEY, owner);
        }
    }
}
