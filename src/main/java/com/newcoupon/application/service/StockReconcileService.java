package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway.DriftEvent;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.IdentityReading;
import java.time.Duration;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 库存对账（ADR-0008 恒等式形态，替代 ADR-0007 的容忍带测量）。
 *
 * <p><b>范式变更</b>：旧方案是"三个不同时刻读三个地方（DB/Redis/JVM计数器），差值和
 * 容忍带比"——时间窗内的流量变化全靠估算（R3 提交泄漏的根源），容忍带=统计推断。
 * 新方案让系统任意时刻满足恒等式（复式记账，每次状态转移都是原子 Lua 配对）：
 *
 * <pre>{@code issued − dbi − pending ≡ 0}
 *   issued  = Redis 侧已发放（deduct Lua +1，cancel Lua −1）
 *   dbi     = DB 侧确认镜像（confirm Lua +1，与令牌出清原子配对）
 *   pending = 在途令牌 ZSET 基数（deduct 入、confirm/cancel 出、崩溃残留按时间清理）</pre>
 *
 * <b>期望值精确为 0，差 1 就是异常</b>——没有容忍带、没有时间窗、不需要压测验证公式。
 *
 * <p><b>两层检查</b>：
 * <ul>
 *   <li><b>第一层（每 60s，一条 Lua 原子快照）</b>：清理过期在途令牌后验恒等式。
 *       崩浪残留的令牌被清理掉的瞬间，差值暴露。连续 2 轮非 0 才进第二层
 *       （慢请求跨过在途 TTL 的瞬时假阳性会自愈：其 confirm 到达后差值归 0）。</li>
 *   <li><b>第二层（确认漂移后）</b>：读 DB 账本诊断归因（confirm-lost / redis-side），
 *       过三道守卫才修复：①在途已清空 ②第二不变式 issued_count == COUNT(user_coupon)
 *       （账本自相矛盾=人工，绝不自动修）③限频落 stock_drift_log（24h 内 ≥5 次升级人工）。</li>
 * </ul>
 *
 * <p><b>为什么保留窄域自动修复</b>（评审裁定，不采纳"对账永远只读"）：
 * 误钉零/镜像滞后在"只告警"下无自愈路径（凌晨人工）；恒等式让"修错"变得可探测
 * （修错=新漂移=下轮再告警），风险与收益重新平衡。修复动作本身是幂等的三本账重置。
 *
 * <p><b>在途 TTL 的物理依据</b>：必须 > DB 事务最坏时长（innodb_lock_wait_timeout 默认 50s
 * + 队列余量），默认 180s。小于它会在系统最异常（锁等待）的时刻产生假告警；
 * 大于它只推迟崩溃检测，不损正确性。这是全方案唯一的时间参数，且锚定物理量。
 *
 * <p>B1 架构约束：本服务读 DB 一律主库直读（当前单库天然满足；M4 引入读写分离后
 * 对账数据源必须绕开从库与缓存）。
 */
@Service
public class StockReconcileService {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileService.class);
    private static final String PATROL_LOCK_KEY = "c:lock:stock-patrol";   // 多实例互斥（L16 重复执行半边）
    private static final String AUDIT_LOCK_KEY = "c:lock:stock-audit";

    private final TemplateRepository templateRepository;
    private final UserCouponRepository userCouponRepository;
    private final com.newcoupon.domain.repository.CouponUsageRepository couponUsageRepository;
    private final RedisStockGateway stockGateway;
    private final StockDriftLogGateway driftLog;
    private final RedisScanLock scanLock;

    private final long inflightTtlMs;
    private final long logMinIntervalMs;

    /** 连续漂移计数（模板→轮数）。并发安全：调度单线程跑 identityCheckAll，此处仅防御性并发 */
    private final Map<Long, Integer> driftRounds = new ConcurrentHashMap<>();
    /** 漂移日志限频（模板→上次落库时刻） */
    private final Map<Long, Long> lastLogAt = new ConcurrentHashMap<>();

    public StockReconcileService(TemplateRepository templateRepository,
                                UserCouponRepository userCouponRepository,
                                com.newcoupon.domain.repository.CouponUsageRepository couponUsageRepository,
                                RedisStockGateway stockGateway,
                                StockDriftLogGateway driftLog,
                                RedisScanLock scanLock,
                                @Value("${coupon.reconcile.inflight-ttl-ms:180000}") long inflightTtlMs,
                                @Value("${coupon.reconcile.log-min-interval-ms:3600000}") long logMinIntervalMs) {
        this.templateRepository = templateRepository;
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.stockGateway = stockGateway;
        this.driftLog = driftLog;
        this.scanLock = scanLock;
        this.inflightTtlMs = inflightTtlMs;
        this.logMinIntervalMs = logMinIntervalMs;
    }

    /**
     * 懒重建（-7 路径，A2'）：条件 SET——清库风暴中只有首个请求真正重建，
     * 其余 no-op；重建后到下一轮对账前的扣减（DECR）天然保留不被覆盖。
     */
    public void lazyRebuildStock(long templateId) {
        CouponTemplate t = templateRepository.findById(templateId).orElse(null);
        if (t == null) {
            return;
        }
        long expected = t.getTotalCount() - t.getIssuedCount();
        if (stockGateway.lazyRebuildStock(templateId, expected)) {
            log.info("redis stock lazily rebuilt: templateId={}, amount={}", templateId, expected);
        }
    }

    /**
     * 第一层：恒等式巡检（每 60s）。返回本轮确认漂移（进入第二层）的模板数——
     * 测试与运维观测用：>0 即存在未被记账原子性覆盖的变更。
     */
    @XxlJob("couponStockPatrolJob")   // XXL-JOB 通道（ADR-0010，路由=第一个，巡检本身单片）；@Scheduled 为降级通道
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public int identityCheckAll() {
        // 多实例互斥：没抢到轮次的实例让路（正确性由幂等 CAS+Lua 门保证，锁消重复扫描负载）
        String owner = "stock-patrol-" + UUID.randomUUID();
        if (!scanLock.tryLock(PATROL_LOCK_KEY, owner, Duration.ofMinutes(10))) {
            log.debug("stock patrol skipped: another instance holds the lock");
            return 0;
        }
        try {
        List<CouponTemplate> published = templateRepository.findPublished();
        int confirmed = 0;
        for (CouponTemplate t : published) {
            try {                                  // R4：单模板异常不拖垮整轮巡检
                if (identityCheckOne(t.getId())) {
                    confirmed++;
                }
            } catch (Exception e) {
                log.error("identity check failed (isolated, continue): templateId={}", t.getId(), e);
            }
        }
        if (confirmed == 0) {
            log.debug("identity check ok: {} published templates, issued − dbi − pend ≡ 0",
                    published.size());
        }
        return confirmed;
        } finally {
            scanLock.unlock(PATROL_LOCK_KEY, owner);
        }
    }

    /**
     * 单模板恒等式检查。连续 2 轮差值非 0 → 确认漂移，进第二层。
     *
     * @return 本轮是否确认漂移并进入第二层
     */
    boolean identityCheckOne(long templateId) {
        IdentityReading r = stockGateway.identity(templateId, inflightTtlMs);
        if (r.delta() == 0) {
            driftRounds.remove(templateId);        // 自愈（慢请求 confirm 到达）或从未漂移
            return false;
        }
        int rounds = driftRounds.merge(templateId, 1, (a, b) -> a + b);
        if (rounds == 1) {
            log.warn("identity drift suspected (round 1): templateId={}, issued={}, dbi={}, pend={}, delta={} "
                    + "→ 下轮复核", templateId, r.issued(), r.confirmed(), r.pending(), r.delta());
            return false;
        }
        log.warn("IDENTITY DRIFT confirmed: templateId={}, issued={}, dbi={}, pend={}, delta={} → deep reconcile",
                templateId, r.issued(), r.confirmed(), r.pending(), r.delta());
        driftRounds.remove(templateId);
        deepReconcile(templateId, r);
        return true;
    }

    /**
     * 第二层：诊断 + 守卫修复。读 DB 账本归因，三道守卫全过才重置三本账。
     * 守卫不过 → 只告警落表（ALARM_ONLY / GUARD_FAILED / DEFERRED_PENDING），不动 Redis。
     */
    void deepReconcile(long templateId, IdentityReading suspected) {
        CouponTemplate t = templateRepository.findById(templateId).orElse(null);
        if (t == null) {
            return;                                // 模板已删，三本账随 key TTL 自然消亡
        }
        // 复测：从疑似到确认隔了一轮巡检，期间慢请求可能已 confirm（假阳性在此止步）
        IdentityReading r = stockGateway.identity(templateId, inflightTtlMs);
        if (r.delta() == 0) {
            log.info("drift self-healed before repair: templateId={}", templateId);
            return;
        }

        long dbTotal = t.getTotalCount();
        long dbIssued = t.getIssuedCount();

        // 守卫①：在途未清空——修复会覆盖在途请求的记账，推迟到下一轮
        if (r.pending() > 0) {
            log.warn("repair deferred (pending={}): templateId={}", r.pending(), templateId);
            recordDrift(new DriftEvent(templateId, r.issued(), r.confirmed(), r.pending(),
                    dbTotal, dbIssued, null, stockGateway.remaining(templateId), r.delta(),
                    "DEFERRED_PENDING", "在途未清，推迟修复"));
            return;
        }

        // 归因（写进日志，供事后追查）：dbDiff>0 = 有已提交未确认（confirm 丢失）；
        // dbDiff==0 且 delta≠0 = Redis 侧自身损坏（issued 错账）
        long dbDiff = dbIssued - r.confirmed();
        String attribution = dbDiff > 0
                ? "confirm-lost(db领先镜像" + dbDiff + ")"
                : "redis-side(借方错账" + r.delta() + ")";

        // 守卫②：第二不变式——DB 账本 issued_count 必须与券明细行数互证。
        // 不等 = 账本自身矛盾（DB 侧 bug/人工改数），此时"以 DB 为准修复"失去依据 → 人工。
        long couponCount = userCouponRepository.countByTemplate(templateId);
        if (couponCount != dbIssued) {
            log.error("GUARD FAILED: issued_count({}) != COUNT(user_coupon)({}) — 账本自相矛盾，"
                    + "冻结自动修复，人工介入: templateId={}, {}", dbIssued, couponCount, templateId, attribution);
            recordDrift(new DriftEvent(templateId, r.issued(), r.confirmed(), r.pending(),
                    dbTotal, dbIssued, couponCount, stockGateway.remaining(templateId), r.delta(),
                    "GUARD_FAILED", attribution));
            return;
        }

        // 三守卫全过：按 DB 口径重置三本账（幂等动作，修错会在下轮恒等式重新暴露）
        long stock = dbTotal - dbIssued;
        boolean repaired = stockGateway.repair(templateId, stock, dbIssued, dbIssued);
        if (!repaired) {
            // P0 修复④（2026-09-12）：守卫①（Java 侧读 pending==0）与 REPAIR 执行之间隔着
            // 守卫②的 MySQL COUNT（秒级窗口），期间新进的预占会被 SET 抹账（TOCTOU）。
            // 在途门已下沉 Lua 原子拦截——这里处理拦截结果：推迟到下轮，绝不带着在途强修。
            log.warn("repair deferred: in-flight reappeared before execution (Lua gate): templateId={}",
                    templateId);
            recordDrift(new DriftEvent(templateId, r.issued(), r.confirmed(), r.pending(),
                    dbTotal, dbIssued, null, stockGateway.remaining(templateId), r.delta(),
                    "DEFERRED_PENDING", "修复执行时在途非空（Lua 门原子拦截），下轮重试"));
            return;
        }
        if (stock > 0) {
            stockGateway.clearSoldout(templateId);  // B4 清除协议：修复恢复了余量必须清标志
        }

        // 守卫③（限频）+ 升级：24h 内 ≥5 条漂移记录 = 漂移源未死，升级人工
        recordDrift(new DriftEvent(templateId, r.issued(), r.confirmed(), r.pending(),
                dbTotal, dbIssued, couponCount, stockGateway.remaining(templateId), r.delta(),
                "REPAIRED", attribution));
        long recent = driftLog.countSince(templateId, LocalDateTime.now().minusHours(24));
        if (recent >= 5) {
            log.error("ESCALATE: templateId={} 24h 内第 {} 次漂移修复——漂移源未消除，需人工排查根因",
                    templateId, recent);
        }
        log.warn("identity repaired: templateId={}, stock={} issued={} dbi={} ({})",
                templateId, stock, dbIssued, dbIssued, attribution);
    }

    /**
     * T+1 全量审计（A8 第二不变式，每日一次）：所有模板的 issued_count 与
     * COUNT(user_coupon) 互证。这是恒等式之外的独立防线——它校验的是 DB 账本本身，
     * 能抓住"Redis 三本账全对但 DB 少记/多记"的场景（如人工直接改库、未来 binlog 链路丢失）。
     */
    @XxlJob("couponStockAuditJob")
    @Scheduled(cron = "${coupon.reconcile.audit-cron:0 47 3 * * ?}", zone = "Asia/Shanghai")
    public void dailyAudit() {
        String owner = "stock-audit-" + UUID.randomUUID();
        if (!scanLock.tryLock(AUDIT_LOCK_KEY, owner, Duration.ofMinutes(30))) {
            log.info("stock audit skipped: another instance holds the lock");
            return;
        }
        try {
        for (CouponTemplate t : templateRepository.findPublished()) {
            try {
                long count = userCouponRepository.countByTemplate(t.getId());
                if (count != t.getIssuedCount()) {
                    log.error("DAILY AUDIT FAILED: templateId={} issued_count={} != COUNT(user_coupon)={} "
                            + "→ 人工介入", t.getId(), t.getIssuedCount(), count);
                    recordDrift(new DriftEvent(t.getId(), 0, 0, 0,
                            t.getTotalCount(), t.getIssuedCount(), count,
                            stockGateway.remaining(t.getId()), 0,
                            "AUDIT_FAILED", "T+1 互证失败：DB 账本与券明细不一致"));
                }
            } catch (Exception e) {
                log.error("daily audit failed (isolated, continue): templateId={}", t.getId(), e);
            }
        }
        // L4（2026-09-15）：占用单×券状态交叉核对——资产维度一致性（库存之外的独立防线）
        crossCheckUsageAndCoupon();
        log.info("daily audit done");
        } finally {
            scanLock.unlock(AUDIT_LOCK_KEY, owner);
        }
    }

    /**
     * L4（2026-09-15，多方评审#2/#7/#11/#12 修正版）：占用单×券状态交叉核对。
     * 三个防假阳性设计：
     * ① A 侧只取"占用超 10 分钟"的合同（复用锁龄审计的阈值语义）——刚发生的 lock/收场
     *    转换在窗口期内自然被排除；
     * ② 截断**前置**早退：任一侧集合触顶（≥5000，分表广播下 LIMIT 是每分表语义），
     *    残缺集合 diff 必产批量假阳性——只落一条 TRUNCATED 告警，不做双向 diff；
     * ③ 每个 diff 候选**点查复核**（本项目"确认后才告警"的惯例，同巡检两轮确认）：
     *    重读券状态+点查占用单——每个状态转换是单券原子事务，二次点查后仍不一致才是真悬挂。
     * 落表不走巡检的按模板限频（评审#4/#8：天级任务无需限频，限频会吞掉同模板多张悬挂券）。
     */
    void crossCheckUsageAndCoupon() {
        final int limit = 5000;
        // A：占用超 10 分钟的合同（在途转换排除；阈值与 L5 锁龄审计同源）
        List<com.newcoupon.domain.coupon.CouponUsage> occupying =
                couponUsageRepository.selectOverdueOccupying(LocalDateTime.now().minusMinutes(10), limit);
        List<com.newcoupon.domain.coupon.UserCoupon> locked = userCouponRepository.selectLocked(limit);

        // 截断前置早退：触顶=数量本身即告警，残缺集合不出逐条结果
        if (occupying.size() >= limit || locked.size() >= limit) {
            log.error("CROSS-CHECK TRUNCATED: occupying={}, locked={} ≥{} ——在途不应有这么多，"
                    + "本轮跳过 diff（残缺集合必产假阳性），请先排查集合为何触顶",
                    occupying.size(), locked.size(), limit);
            recordDriftDirect(new DriftEvent(0, 0, 0, 0, 0, 0, null, null, 0,
                    "CROSSCHECK_TRUNCATED",
                    "交叉核对集合触顶: occupying=" + occupying.size() + ", locked=" + locked.size()));
            return;
        }

        java.util.Set<Long> lockedIds = new java.util.HashSet<>();
        for (com.newcoupon.domain.coupon.UserCoupon c : locked) {
            lockedIds.add(c.getId());
        }
        java.util.Set<Long> occupyingCouponIds = new java.util.HashSet<>();
        for (com.newcoupon.domain.coupon.CouponUsage u : occupying) {
            occupyingCouponIds.add(u.getUserCouponId());
            if (!lockedIds.contains(u.getUserCouponId())
                    && stillMismatchUsageHangs(u.getUserCouponId())) {   // 点查复核：二次确认
                Long tplId = userCouponRepository.findById(u.getUserCouponId())
                        .map(com.newcoupon.domain.coupon.UserCoupon::getTemplateId).orElse(0L);
                log.error("CROSS-CHECK USAGE_HANGS: 占用中合同但券非锁定态（复核确认）: couponId={}, "
                        + "usageNo={}, orderNo={} → 人工",
                        u.getUserCouponId(), u.getUsageNo(), u.getOrderNo());
                recordDriftDirect(new DriftEvent(tplId, 0, 0, 0, 0, 0, null, null, 0,
                        "USAGE_HANGS", "占用中合同但券非锁定态(复核确认): couponId=" + u.getUserCouponId()
                                + ", usageNo=" + u.getUsageNo() + ", orderNo=" + u.getOrderNo()));
            }
        }
        for (com.newcoupon.domain.coupon.UserCoupon c : locked) {
            if (!occupyingCouponIds.contains(c.getId())
                    && stillMismatchLockHangs(c.getId())) {   // 点查复核：二次确认
                log.error("CROSS-CHECK LOCK_HANGS: 锁定中券但无占用中合同（复核确认）: couponId={}, "
                        + "templateId={} → 人工", c.getId(), c.getTemplateId());
                recordDriftDirect(new DriftEvent(c.getTemplateId(), 0, 0, 0, 0, 0, null, null, 0,
                        "LOCK_HANGS", "锁定中券但无占用中合同(复核确认): couponId=" + c.getId()));
            }
        }
    }

    /** USAGE_HANGS 复核：占用单仍占用中 且 券当前仍非锁定（每个转换是单券原子事务，
     *  两次读之间状态自洽——仍不一致即真悬挂，假阳性窗口收敛到近零） */
    private boolean stillMismatchUsageHangs(long couponId) {
        boolean usageStillOccupying =
                couponUsageRepository.findOccupyingByCouponId(couponId).isPresent();
        boolean couponStillNotLocked = userCouponRepository.findById(couponId)
                .map(c -> c.getStatus() != com.newcoupon.domain.coupon.CouponStatus.LOCKED)
                .orElse(true);   // 券已不在=仍是不一致（合同还挂着）
        return usageStillOccupying && couponStillNotLocked;
    }

    /** LOCK_HANGS 复核：券仍锁定 且 仍无占用中合同 */
    private boolean stillMismatchLockHangs(long couponId) {
        boolean couponStillLocked = userCouponRepository.findById(couponId)
                .map(c -> c.getStatus() == com.newcoupon.domain.coupon.CouponStatus.LOCKED)
                .orElse(false);
        boolean noOccupyingUsage =
                couponUsageRepository.findOccupyingByCouponId(couponId).isEmpty();
        return couponStillLocked && noOccupyingUsage;
    }

    /** L4 专用落表：不走巡检的按模板限频（评审#4/#8——天级任务、条数天然受真实异常数约束） */
    private void recordDriftDirect(DriftEvent e) {
        try {
            driftLog.record(e);
        } catch (RuntimeException ex) {
            log.error("cross-check drift record failed (must not block audit): {}", e, ex);
        }
    }

    /** 落漂移日志（限频：同模板 logMinIntervalMs 内只记一条，防每分钟巡检刷爆表） */
    private void recordDrift(DriftEvent e) {
        long now = System.currentTimeMillis();
        Long last = lastLogAt.get(e.templateId());
        if (last != null && now - last < logMinIntervalMs) {
            return;
        }
        lastLogAt.put(e.templateId(), now);
        try {
            driftLog.record(e);
        } catch (Exception ex) {
            log.error("drift log write failed (must not block reconcile): {}", e, ex);
        }
    }
}
