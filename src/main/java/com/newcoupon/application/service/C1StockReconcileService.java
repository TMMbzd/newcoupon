package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * C1 最小对账（ADR-0018 §3.8 / ADR-0021 Phase 1）。
 *
 * <p>守住可解释的等式：
 * <ol>
 *   <li>{@code SUM(stock_shard.quota) == coupon_template.total_count}；</li>
 *   <li>{@code SUM(stock_shard.used) == COUNT(user_coupon)}；</li>
 *   <li>每片 {@code 0 <= used <= quota}、行数 = N_eff、status 合法。</li>
 * </ol>
 *
 * <p>差异只告警 + 落漂移表，**不自动改库存**：自动“修平”会把真正原因
 * （旁路写入/手工 SQL/迁移残留）抹掉。人工修复必须走管理工具并留审计。
 *
 * <p>多实例：用 Redis 锁单实例跑；两段式复核（首次不一致后短延迟重读）降低
 * 并发提交造成的假阳性。COUNT 走 64 张 user_coupon 分表，属于低频对账，不在领券热路径。
 */
@Service
public class C1StockReconcileService {

    private static final Logger log = LoggerFactory.getLogger(C1StockReconcileService.class);
    private static final String AUDIT_LOCK_KEY = "c:lock:c1-stock-audit";
    private static final Duration AUDIT_LOCK_TTL = Duration.ofMinutes(10);
    private static final long RECHECK_DELAY_MS = 200;

    private final TemplateRepository templateRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponStockShardMapper shardMapper;
    private final StockDriftLogGateway driftLog;
    private final RedisScanLock scanLock;
    private final MeterRegistry meterRegistry;

    public C1StockReconcileService(TemplateRepository templateRepository,
                                   UserCouponRepository userCouponRepository,
                                   CouponStockShardMapper shardMapper,
                                   StockDriftLogGateway driftLog,
                                   RedisScanLock scanLock,
                                   MeterRegistry meterRegistry) {
        this.templateRepository = templateRepository;
        this.userCouponRepository = userCouponRepository;
        this.shardMapper = shardMapper;
        this.driftLog = driftLog;
        this.scanLock = scanLock;
        this.meterRegistry = meterRegistry;
    }

    /** 供测试/运维手动触发；返回发现差异的模板数。 */
    public int auditAll() {
        String owner = "c1-stock-audit-" + UUID.randomUUID();
        boolean locked;
        try {
            locked = scanLock.tryLock(AUDIT_LOCK_KEY, owner, AUDIT_LOCK_TTL);
        } catch (RuntimeException e) {
            log.warn("C1 stock audit lock failed; skip this round", e);
            return 0;
        }
        if (!locked) {
            log.debug("C1 stock audit skipped: another instance holds {}", AUDIT_LOCK_KEY);
            return 0;
        }
        long started = System.nanoTime();
        try {
            int drifted = 0;
            for (CouponTemplate t : templateRepository.findPublished()) {
                try {
                    if (auditOne(t)) {
                        drifted++;
                    }
                } catch (RuntimeException e) {
                    log.error("C1 stock audit failed (isolated): templateId={}", t.getId(), e);
                }
            }
            meterRegistry.timer("c1_stock_reconcile_duration_seconds")
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            return drifted;
        } finally {
            scanLock.unlock(AUDIT_LOCK_KEY, owner);
        }
    }

    boolean auditOne(CouponTemplate t) {
        Snapshot first = snapshot(t);
        if (first.drifts.isEmpty()) {
            return false;
        }
        // 两段式：并发提交可能让三个独立读落在同一事务窗口两侧；短延迟重读仍不一致才落账。
        try {
            Thread.sleep(RECHECK_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Snapshot second = snapshot(t);
        if (second.drifts.isEmpty()) {
            return false;
        }
        for (String type : second.drifts) {
            meterRegistry.counter("c1_stock_drift_total", "type", type).increment();
        }
        log.error("C1 STOCK AUDIT FAILED: templateId={} drifts={} quota={} used={} coupons={}",
                t.getId(), second.drifts, second.quota, second.used, second.coupons);
        record(t, second, String.join(",", second.drifts));
        return true;
    }

    private Snapshot snapshot(CouponTemplate t) {
        List<String> drifts = new ArrayList<>();
        int activeVersion = t.getStockLayoutVersion();
        List<CouponStockShardPO> rows = shardMapper.selectByTemplate(t.getId());
        long quota = rows.stream().mapToLong(r -> nz(r.getQuota())).sum();
        long used = rows.stream().mapToLong(r -> nz(r.getUsed())).sum();
        long coupons = userCouponRepository.countByTemplate(t.getId());
        long activeRows = rows.stream()
                .filter(r -> StockLayoutInvariants.active(r, activeVersion))
                .count();

        // 全局不变量：所有版本合计（退役行 quota=used 仍计入总量/已发）。
        if (!StockLayoutInvariants.globalQuotaMatches(quota, t.getTotalCount())) {
            drifts.add("C1_AUDIT_QUOTA");
        }
        if (!StockLayoutInvariants.globalUsedMatches(used, coupons)) {
            drifts.add("C1_AUDIT_USED");
        }
        if (!StockLayoutInvariants.activeRowCountMatches(activeRows, t.getStockShardCount())) {
            drifts.add("C1_AUDIT_ROWS");
        }
        for (CouponStockShardPO row : rows) {
            if (!StockLayoutInvariants.layoutVersionValid(row)) {
                drifts.add("C1_AUDIT_LAYOUT_VERSION");
                break;
            }
            if (!StockLayoutInvariants.rowRangeValid(row)) {
                drifts.add("C1_AUDIT_SHARD_RANGE");
                break;
            }
            if (!StockLayoutInvariants.statusValid(row)) {
                drifts.add("C1_AUDIT_STATUS");
                break;
            }
            boolean active = StockLayoutInvariants.active(row, activeVersion);
            if (!active && !StockLayoutInvariants.retiredRowBalanced(row, activeVersion)) {
                // 版本号 > active（回滚残留）或退役行没做 status=1/quota=used 都算脏数据
                drifts.add("C1_AUDIT_RETIRED");
                break;
            }
        }
        return new Snapshot(rows, quota, used, coupons, activeVersion, drifts);
    }

    private void record(CouponTemplate t, Snapshot s, String action) {
        String detail = "C1布局/账本不一致: drifts=" + action
                + ", layoutVersion=" + s.activeVersion
                + ", rows=" + s.rows.size() + ", activeRows="
                + s.rows.stream().filter(r -> StockLayoutInvariants.active(r, s.activeVersion)).count()
                + ", Σquota=" + s.quota
                + ", Σused=" + s.used + ", COUNT(user_coupon)=" + s.coupons;
        try {
            driftLog.record(new StockDriftLogGateway.DriftEvent(
                    t.getId(), 0, 0, 0, t.getTotalCount(), s.used, s.coupons,
                    s.quota, s.used - s.coupons, action, detail, null, null));
        } catch (RuntimeException e) {
            log.error("C1 stock audit drift log failed: templateId={}", t.getId(), e);
        }
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private record Snapshot(List<CouponStockShardPO> rows, long quota, long used,
                            long coupons, int activeVersion, List<String> drifts) {
    }

    /** 默认每小时；测试/运维可直接调用 {@link #auditAll()} 触发；XXL-JOB 单实例路由。 */
    @XxlJob("couponStockReconcileJob")
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void scheduledAudit() {
        int drifted = auditAll();
        if (drifted > 0) {
            log.error("C1 stock audit round found {} drifted template(s)", drifted);
        }
    }
}
