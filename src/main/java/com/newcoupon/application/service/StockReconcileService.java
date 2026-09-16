package com.newcoupon.application.service;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway.DriftEvent;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.ConservationReading;
import com.newcoupon.infrastructure.redis.RedisStockGateway.RebuildResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 库存守恒对账（ADR-0009 四状态模型，2026-09-16 迁移；取代 ADR-0008 恒等式形态）。
 *
 * <p><b>守恒式（唯一第一层不变量）</b>：{@code stk + ZCARD(pend,清扫后) + dbi + void ≡ total}。
 * 旧恒等式 issued−dbi−pend≡0 三盲区（红灯实测）：stk 不在式内、三键整体平移、全清库空真。
 *
 * <p><b>三层检查（§3）</b>：
 * ① 守恒巡检（60s，一条 Lua 读五键）——两轮确认，确认步按主键重读新鲜 total/ic（防列表行陈旧）；
 * ② 方向性镜像（同轮）——幻影确认臂（dbi&gt;新鲜ic 才告警，陈旧 ic 预筛不告警）+
 *    confirm-lost 臂（ic−dbi&gt;0 且清扫后 pend==0 才修复——方向性判据消灭流量期告警永动）；
 * ③ T+1 审计（DB 账实互证）+ 占用单×券状态交叉核对（L4）。
 *
 * <p><b>深对账独立线程（v2 P1）</b>：deepReconcile 在单线程执行器运行，巡检线程每模板
 * 只做"一条 Lua+判定"——漂移风暴下 COUNT 串行不会拉长巡检循环（放大一切陈旧窗）。
 *
 * <p><b>极窄域自动修复（§4 白名单五条件）</b>：两轮确认+新鲜读数｜模板可领｜在途已清（Lua ZCARD 门）｜
 * DB 账实互证｜双键 CAS 栅栏（Lua 内）。FORCE 按业务状态分支推导（非照抄旧值）。
 * 只告警永不动手：账实矛盾（首条升级人工）｜total 调小｜活跃态 void 异常｜囚禁｜transient 熔断｜DEFERRED≥30。
 *
 * <p><b>运行安全件（v3 修订6/7/8）</b>：巡检心跳键（静默=最危险故障）｜告警风暴聚合（单轮&gt;5 模板发全局
 * P1 抑制单模板）｜总开关+影子模式（推导落表不执行）｜连续 2 次 FORCE 熔断修复环｜维护标记键豁免｜
 * transient 取消率计数（5 分钟窗，熔断动作归 M2）。
 */
@Service
public class StockReconcileService {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileService.class);
    private static final String PATROL_LOCK_KEY = "c:lock:stock-patrol";
    private static final String AUDIT_LOCK_KEY = "c:lock:stock-audit";
    private static final String HEARTBEAT_KEY = "c:hb:patrol";           // v3-6：过期即一级告警
    private static final String MAINT_PREFIX = "c:maint:";               // v3-8：人工维护豁免
    private static final String FORCE_COUNT_PREFIX = "c:frce:";          // v3-8：连续 FORCE 计数
    private static final String TRANSIENT_WINDOW_PREFIX = "c:tc5:";      // transient 5 分钟窗计数

    private final TemplateRepository templateRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final RedisStockGateway stockGateway;
    private final StockDriftLogGateway driftLog;
    private final RedisScanLock scanLock;

    private final long inflightTtlMs;
    private final long logMinIntervalMs;
    private final String repairMode;        // force | shadow（v3-8 影子模式先行）
    private final int transientBurstThreshold;

    /** 连续漂移计数（模板→轮数）；确认由 deep 最终结果驱动清零（ADR §5 修位） */
    private final Map<Long, Integer> driftRounds = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastLogAt = new ConcurrentHashMap<>();
    /** v2：deep 独立单线程执行器+队列上界——巡检线程不被 COUNT 拖住 */
    private final ExecutorService deepExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stock-deep-reconcile");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Boolean> deepInFlight = new ConcurrentHashMap<>();
    private final AtomicLong deferredRounds = new AtomicLong();   // DEFERRED 连续轮计数（全局近似，30 轮告警）
    private final AtomicBoolean heartbeatEverWritten = new AtomicBoolean(false);

    public StockReconcileService(TemplateRepository templateRepository,
                                UserCouponRepository userCouponRepository,
                                CouponUsageRepository couponUsageRepository,
                                RedisStockGateway stockGateway,
                                StockDriftLogGateway driftLog,
                                RedisScanLock scanLock,
                                @Value("${coupon.reconcile.inflight-ttl-ms:180000}") long inflightTtlMs,
                                @Value("${coupon.reconcile.log-min-interval-ms:3600000}") long logMinIntervalMs,
                                @Value("${coupon.reconcile.auto-repair.mode:shadow}") String repairMode,
                                @Value("${coupon.reconcile.transient-burst-threshold:500}") int transientBurstThreshold) {
        this.templateRepository = templateRepository;
        this.userCouponRepository = userCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.stockGateway = stockGateway;
        this.driftLog = driftLog;
        this.scanLock = scanLock;
        this.inflightTtlMs = inflightTtlMs;
        this.logMinIntervalMs = logMinIntervalMs;
        this.repairMode = repairMode;
        this.transientBurstThreshold = transientBurstThreshold;
    }

    // ==================== 巡检（第一层：守恒式 + 第二层：方向性镜像） ====================

    /**
     * 守恒巡检（60s）。返回本轮确认漂移（提交 deep）的模板数。
     */
    @XxlJob("couponStockPatrolJob")
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public int conservationCheckAll() {
        String owner = "stock-patrol-" + java.util.UUID.randomUUID();
        if (!scanLock.tryLock(PATROL_LOCK_KEY, owner, Duration.ofMinutes(10))) {
            log.debug("stock patrol skipped: another instance holds the lock");
            return 0;
        }
        try {
            writeHeartbeat();
            checkHeartbeatLiveness();
            List<CouponTemplate> published = templateRepository.findPublished();
            int confirmed = 0;
            int suspectedThisRound = 0;
            for (CouponTemplate t : published) {
                try {
                    if (underMaintenance(t.getId())) {
                        continue;   // v3-8：维护标记豁免
                    }
                    if (mirrorCheck(t)) {
                        confirmed++;   // 镜像任一臂确认即提交 deep
                        continue;
                    }
                    if (conservationCheckOne(t)) {
                        confirmed++;
                        suspectedThisRound++;
                    }
                } catch (Exception e) {
                    log.error("conservation check failed (isolated, continue): templateId={}", t.getId(), e);
                }
            }
            checkTransientBurst(published);
            if (suspectedThisRound > 5 || suspectedThisRound * 100 > Math.max(published.size(), 1)) {
                log.error("PATROL STORM: 单轮确认漂移模板数={} (>{}/>1%)——全局事件 P1，抑制单模板告警",
                        suspectedThisRound, 5);
            }
            return confirmed;
        } catch (Exception e) {
            log.error("stock patrol round failed (next round retries)", e);
            return -1;
        } finally {
            scanLock.unlock(PATROL_LOCK_KEY, owner);
        }
    }

    /** 兼容旧方法名（XXL-JOB 任务名不变） */
    public int identityCheckAll() {
        return conservationCheckAll();
    }

    /** deep 执行器是否空闲（测试等待异步对账完成用） */
    public boolean deepQueueIdle() {
        return deepInFlight.isEmpty();
    }

    /** 单模板巡检（集成测试/运维精确断言用）：镜像+守恒一轮，true=该模板确认异常 */
    public boolean checkOne(long templateId) {
        CouponTemplate t = templateRepository.findById(templateId).orElse(null);
        if (t == null) {
            return false;
        }
        if (mirrorCheck(t)) {
            return true;
        }
        return conservationCheckOne(t);
    }

    /**
     * 方向性镜像（§3.2 两级判据）。@return true=确认异常（已提交 deep/已告警）。
     * 幻影确认臂：dbi > ic_列表 → 按主键重读新鲜 ic 再判（陈旧 ic 不告警——零误报论证：
     * confirm 的 dbi+1 严格晚于其提交的 ic+1，新鲜 ic 必然包含 dbi 的全部来源提交）。
     * confirm-lost 臂：ic−dbi>0 且清扫后 pend==0 → 提交 deep（诚实态 ic−dbi≤活跃在途恒成立）。
     */
    boolean mirrorCheck(CouponTemplate t) {
        ConservationReading r = stockGateway.conservation(t.getId(), inflightTtlMs);
        long icList = t.getIssuedCount();
        if (r.dbi() > icList) {
            CouponTemplate fresh = templateRepository.findById(t.getId()).orElse(null);
            if (fresh == null) {
                return false;
            }
            if (r.dbi() > fresh.getIssuedCount()) {
                log.error("PHANTOM CONFIRM: dbi({}) > 新鲜ic({})——存在被抹掉的已提交事务（P0 Bug① 签名）"
                        + " → 立即叫醒级告警: templateId={}", r.dbi(), fresh.getIssuedCount(), t.getId());
                recordDrift(new DriftEvent(t.getId(), 0, r.dbi(), r.pend(),
                        fresh.getTotalCount(), fresh.getIssuedCount(), null, r.stk(),
                        r.dbi() - fresh.getIssuedCount(), "PHANTOM_CONFIRM",
                        "幻影确认：dbi 超过新鲜 ic——立即叫醒级", r.voidRetired(), null));
                return true;
            }
            return false;   // 陈旧列表 ic 的假阳——新鲜读数止步
        }
        long gap = icList - r.dbi();
        if (gap > 0 && r.pend() == 0) {
            return submitDeep(t.getId(), "confirm-lost(ic领先镜像" + gap + ")");
        }
        return false;
    }

    /**
     * 守恒单模板检查：两轮确认（第一轮怀疑/第二轮定罪），确认步在 deep 内用新鲜读数。
     */
    boolean conservationCheckOne(CouponTemplate t) {
        ConservationReading r = stockGateway.conservation(t.getId(), inflightTtlMs);
        if (r.sum() == t.getTotalCount()) {
            return false;   // 和守恒（本轮清零由 deep 结果驱动——见 §5 修位）
        }
        int rounds = driftRounds.merge(t.getId(), 1, (a, b) -> a + b);
        if (rounds == 1) {
            log.warn("conservation drift suspected (round 1): templateId={}, stk={}, pend={}, dbi={}, void={}, "
                    + "sum={} ≠ total={} → 下轮复核", t.getId(), r.stk(), r.pend(), r.dbi(), r.voidRetired(),
                    r.sum(), t.getTotalCount());
            return false;
        }
        log.warn("CONSERVATION DRIFT confirmed: templateId={}, 五键读数 stk={} pend={} dbi={} void={} sum={} "
                + "≠ total={} → deep reconcile", t.getId(), r.stk(), r.pend(), r.dbi(), r.voidRetired(),
                r.sum(), t.getTotalCount());
        driftRounds.remove(t.getId());
        return submitDeep(t.getId(), "conservation(sum=" + r.sum() + ")");
    }

    // ==================== 深对账（独立线程：白名单+分支推导+修复） ====================

    /** 提交深对账（去重：同模板在途的 deep 只有一个） */
    boolean submitDeep(long templateId, String reason) {
        if (deepInFlight.putIfAbsent(templateId, Boolean.TRUE) != null) {
            return true;   // 已有在途 deep——本轮视为已提交
        }
        deepExecutor.submit(() -> {
            try {
                deepReconcile(templateId, reason);
            } catch (Exception e) {
                log.error("deep reconcile failed: templateId={}", templateId, e);
            } finally {
                deepInFlight.remove(templateId);
            }
        });
        return true;
    }

    /** 测试直调入口（同步执行） */
    void deepReconcileSync(long templateId, String reason) {
        deepReconcile(templateId, reason);
    }

    /**
     * 深对账：白名单五条件逐关（§4），通过→REBUILD force/shadow；不过→分类告警。
     * 全部使用新鲜读数（主键重读）。
     */
    void deepReconcile(long templateId, String reason) {
        CouponTemplate t = templateRepository.findById(templateId).orElse(null);
        if (t == null) {
            driftRounds.remove(templateId);
            return;
        }
        ConservationReading r = stockGateway.conservation(templateId, inflightTtlMs);
        long total = t.getTotalCount();
        long ic = t.getIssuedCount();

        // 白名单②：模板可领（publish=1 AND approve=1 AND 窗口内）——不可领的走只告警通道
        boolean receivable = t.getPublishStatus() == com.newcoupon.domain.coupon.PublishStatus.PUBLISHED
                && t.getApproveStatus() == com.newcoupon.domain.coupon.ApproveStatus.APPROVED;

        // 只告警永不动手清单（§4）
        if (!receivable) {
            // 退役/下线态：守恒仍应成立（void 吸收），只在破缺时告警
            if (r.sum() != total) {
                log.warn("CONSERVATION BROKEN on 不可领模板（只告警）: templateId={}, sum={} ≠ total={}",
                        templateId, r.sum(), total);
                recordDrift(new DriftEvent(templateId, 0, r.dbi(), r.pend(), total, ic, null, r.stk(),
                        r.sum() - total, "ALARM_ONLY", "不可领模板守恒破缺：" + reason, r.voidRetired(), null));
            } else {
                driftRounds.remove(templateId);   // 干净——清零（修位：deep 结果驱动）
            }
            return;
        }
        if (r.pend() > 0) {
            recordDrift(new DriftEvent(templateId, 0, r.dbi(), r.pend(), total, ic, null, r.stk(), 0,
                    "DEFERRED_PENDING", "在途未清，本轮推迟（" + reason + "）", r.voidRetired(), null));
            deferredRounds.incrementAndGet();
            if (deferredRounds.get() >= 30) {
                log.error("DEFERRED 连续 {} 轮——在途永不清（泄漏或 TTL 配错），升级人工", deferredRounds.get());
            }
            return;
        }
        deferredRounds.set(0);
        // 白名单④：DB 账实互证（首条即升级人工——ic 不可信时修复失去基准）
        Long couponCount = userCouponRepository.countByTemplate(templateId);
        if (couponCount != ic) {
            log.error("GUARD FAILED: ic({}) != COUNT(user_coupon)({})——账本自相矛盾，冻结一切自动动作: "
                    + "templateId={}", ic, couponCount, templateId);
            recordDrift(new DriftEvent(templateId, 0, r.dbi(), r.pend(), total, ic, couponCount, r.stk(),
                    0, "GUARD_FAILED", reason, r.voidRetired(), null));
            return;
        }
        // 只告警形态：total 被调小 / 活跃态 void 异常——仅对【活跃态】检查（so 已立或 ic≥total
        // 的退役态，污染余量正是 FORCE retired 分支要归零的对象，不得在此拦截——2026-09-16
        // 集成测试实锤：旧条件 stk+dbi>total 把售罄污染场景误判成只告警，FORCE 永远不被触达）
        boolean retired = r.soldOut() || ic >= total;
        if (!retired && (r.stk() + r.dbi() > total || r.voidRetired() > total - ic)) {
            log.error("IMPOSSIBLE STATE: 活跃态 stk({})+dbi({}) > total({}) 或 void({})>total−ic——"
                    + "total 被调小或 void 异常，只告警: templateId={}", r.stk(), r.dbi(), total,
                    r.voidRetired(), templateId);
            recordDrift(new DriftEvent(templateId, 0, r.dbi(), r.pend(), total, ic, couponCount, r.stk(),
                    r.sum() - total, "ALARM_ONLY", "total 调小/void 异常：" + reason, r.voidRetired(), null));
            return;
        }
        // 囚禁告警（§4）：publish=1 且 stk==0 且无 so 且 dbi+void==total 且 dbi<total → 剩余被 void 囚禁
        if (r.stk() == 0 && !r.soldOut() && r.dbi() + r.voidRetired() == total && r.dbi() < total) {
            log.error("IMPRISONED: 剩余量被 void 囚禁（stk=0 无 so，dbi+void=total，dbi<total）"
                    + "——重新上线未走协议？: templateId={}", templateId);
            recordDrift(new DriftEvent(templateId, 0, r.dbi(), r.pend(), total, ic, couponCount, r.stk(),
                    0, "IMPRISONED", reason, r.voidRetired(), null));
            return;
        }

        // 白名单⑤+FORCE：连续 2 次熔断修复环（v3-8）
        Long forceCount = forceCount(templateId);
        if (forceCount != null && forceCount >= 2) {
            log.error("REPAIR LOOP BREAKER: templateId={} 已连续 {} 次 FORCE 仍漂移——熔断修复环，人工",
                    templateId, forceCount);
            recordDrift(new DriftEvent(templateId, 0, r.dbi(), r.pend(), total, ic, couponCount, r.stk(),
                    r.sum() - total, "REPAIR_LOOP", reason, r.voidRetired(), null));
            return;
        }

        String mode = "shadow".equals(repairMode) ? "shadow" : "force";
        RebuildResult res = stockGateway.rebuild(templateId, mode, total, ic, inflightTtlMs,
                r.dbi(), r.voidRetired());   // 栅栏快照=与修复参数同批读得（非目标 ic）
        if (res.ret() == -1) {
            recordDrift(new DriftEvent(templateId, 0, r.dbi(), r.pend(), total, ic, couponCount, r.stk(),
                    0, "DEFERRED_PENDING", "执行瞬间在途非空（Lua 门）：" + reason, r.voidRetired(), null));
            return;
        }
        if (res.ret() == -2) {
            recordDrift(new DriftEvent(templateId, 0, r.dbi(), r.pend(), total, ic, couponCount, r.stk(),
                    0, "BARRIER_ABORT", "双键栅栏中止（读数与执行间混入 confirm/terminal）：" + reason,
                    r.voidRetired(), null));
            return;
        }
        if ("force".equals(mode) && res.rebuilt() && res.stk() > 0) {
            stockGateway.clearSoldout(templateId);   // B4 清除协议（影子模式不动任何键）
        }
        String action = "shadow".equals(mode) ? "SHADOW_REPAIR" : "REPAIRED";
        recordDrift(new DriftEvent(templateId, 0, res.dbi(), r.pend(), total, ic, couponCount, res.stk(),
                r.sum() - total, action, reason + "→" + mode, res.voidRetired(), null));
        driftRounds.remove(templateId);   // 修位：deep 完成（修过或影子）即清零
        if ("force".equals(mode)) {
            bumpForceCount(templateId);
            log.warn("conservation repaired(FORCE): templateId={}, stk={}, dbi={}, void={} ({})",
                    templateId, res.stk(), res.dbi(), res.voidRetired(), reason);
        } else {
            log.warn("conservation SHADOW repair (derived only, no SET): templateId={}, 应为 stk={}, dbi={}, "
                    + "void={} ({})", templateId, res.stk(), res.dbi(), res.voidRetired(), reason);
        }
        escalateIfRepeated(templateId);
    }

    // ==================== 懒重建（-7 领券可用性路径——总开关不拦） ====================

    public void lazyRebuildStock(long templateId) {
        CouponTemplate t = templateRepository.findById(templateId).orElse(null);
        if (t == null) {
            return;
        }
        RebuildResult res = stockGateway.rebuild(templateId, "nx",
                t.getTotalCount(), t.getIssuedCount(), inflightTtlMs, 0, 0);
        if (res.rebuilt()) {
            log.info("conservation lazy rebuild (NX): templateId={}, stk={}", templateId, res.stk());
        }
    }

    // ==================== T+1 审计 + 交叉核对（第三层） ====================

    @XxlJob("couponStockAuditJob")
    @Scheduled(cron = "${coupon.reconcile.audit-cron:0 47 3 * * ?}", zone = "Asia/Shanghai")
    public void dailyAudit() {
        String owner = "stock-audit-" + java.util.UUID.randomUUID();
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
                                "AUDIT_FAILED", "T+1 互证失败：DB 账本与券明细不一致",
                                stockGateway.conservation(t.getId(), inflightTtlMs).voidRetired(), null));
                    }
                } catch (Exception e) {
                    log.error("daily audit failed (isolated, continue): templateId={}", t.getId(), e);
                }
            }
            // L4：占用单×券状态交叉核对（2026-09-15）
            crossCheckUsageAndCoupon();
            // v3-6：审计当日摘要（心跳）
            scanLock.unlock(AUDIT_LOCK_KEY, owner);   // 先还锁再写摘要（摘要非互斥）
            recordAuditSummary();
            log.info("stock daily audit done");
        } catch (Exception e) {
            log.error("stock daily audit failed", e);
        } finally {
            scanLock.unlock(AUDIT_LOCK_KEY, owner);
        }
    }

    /**
     * L4（2026-09-15，多方评审#2/#7/#11/#12 修正版）：占用单×券状态交叉核对。
     * 三个防假阳性设计：A 侧只取占用超 10 分钟（在途排除）；截断前置早退；候选点查复核。
     * 落表不走巡检限频（天级任务无需限频）。
     */
    void crossCheckUsageAndCoupon() {
        final int limit = 5000;
        List<com.newcoupon.domain.coupon.CouponUsage> occupying =
                couponUsageRepository.selectOverdueOccupying(LocalDateTime.now().minusMinutes(10), limit);
        List<com.newcoupon.domain.coupon.UserCoupon> locked = userCouponRepository.selectLocked(limit);

        if (occupying.size() >= limit || locked.size() >= limit) {
            log.error("CROSS-CHECK TRUNCATED: occupying={}, locked={} ≥{} ——本轮跳过 diff（残缺集合必产"
                    + "假阳性），请先排查集合为何触顶", occupying.size(), locked.size(), limit);
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
                    && stillMismatchUsageHangs(u.getUserCouponId())) {
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
                    && stillMismatchLockHangs(c.getId())) {
                log.error("CROSS-CHECK LOCK_HANGS: 锁定中券但无占用中合同（复核确认）: couponId={}, "
                        + "templateId={} → 人工", c.getId(), c.getTemplateId());
                recordDriftDirect(new DriftEvent(c.getTemplateId(), 0, 0, 0, 0, 0, null, null, 0,
                        "LOCK_HANGS", "锁定中券但无占用中合同(复核确认): couponId=" + c.getId()));
            }
        }
    }

    private boolean stillMismatchUsageHangs(long couponId) {
        boolean usageStillOccupying =
                couponUsageRepository.findOccupyingByCouponId(couponId).isPresent();
        boolean couponStillNotLocked = userCouponRepository.findById(couponId)
                .map(c -> c.getStatus() != com.newcoupon.domain.coupon.CouponStatus.LOCKED)
                .orElse(true);
        return usageStillOccupying && couponStillNotLocked;
    }

    private boolean stillMismatchLockHangs(long couponId) {
        boolean couponStillLocked = userCouponRepository.findById(couponId)
                .map(c -> c.getStatus() == com.newcoupon.domain.coupon.CouponStatus.LOCKED)
                .orElse(false);
        boolean noOccupyingUsage =
                couponUsageRepository.findOccupyingByCouponId(couponId).isEmpty();
        return couponStillLocked && noOccupyingUsage;
    }

    // ==================== 运行安全件（心跳/风暴/计数） ====================

    /** v3-6：巡检心跳（TTL=3×周期）——巡检自身静默是最危险的故障 */
    private void writeHeartbeat() {
        try {
            stockGatewayHeartbeat();
            heartbeatEverWritten.set(true);
        } catch (RuntimeException e) {
            log.error("heartbeat write failed", e);
        }
    }

    private void stockGatewayHeartbeat() {
        if (!auxReady()) {
            return;
        }
        // 直接 SET 心跳键（不经 Lua——心跳必须独立于业务脚本）
        aux().opsForValue().set(HEARTBEAT_KEY, String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(180));
    }

    private void checkHeartbeatLiveness() {
        if (!heartbeatEverWritten.get()) {
            return;   // 首轮
        }
        try {
            String hb = aux().opsForValue().get(HEARTBEAT_KEY);
            if (hb == null && heartbeatPrevRoundAlive.get()) {
                log.error("HEARTBEAT EXPIRED: 巡检心跳键缺失且上轮还在——巡检曾中断，立即叫醒级告警");
            }
            heartbeatPrevRoundAlive.set(hb != null);
        } catch (RuntimeException ignore) {
        }
    }

    private final AtomicBoolean heartbeatPrevRoundAlive = new AtomicBoolean(true);

    private void recordAuditSummary() {
        try {
            aux().opsForValue().set("c:hb:audit:" + LocalDateTime.now().toLocalDate(),
                    String.valueOf(System.currentTimeMillis()), Duration.ofDays(3));
        } catch (RuntimeException e) {
            log.error("audit summary write failed", e);
        }
    }

    /** transient 取消率熔断（§4 只告警；动作归 M2） */
    private void checkTransientBurst(List<CouponTemplate> published) {
        for (CouponTemplate t : published) {
            try {
                String v = aux().opsForValue().get(TRANSIENT_WINDOW_PREFIX + "{" + t.getId() + "}");
                if (v != null && Long.parseLong(v) >= transientBurstThreshold) {
                    log.error("TRANSIENT BURST: templateId={} 5 分钟内 transient 取消 {} 次 ≥{}——"
                            + "纯 DB 劣化段 N1 检测（守恒全程绿灯的段），熔断动作归 M2", t.getId(), v,
                            transientBurstThreshold);
                    recordDrift(new DriftEvent(t.getId(), 0, 0, 0, t.getTotalCount(), t.getIssuedCount(),
                            null, null, 0, "TRANSIENT_BURST", "5min transient 取消率超阈", null, null));
                }
            } catch (RuntimeException ignore) {
            }
        }
    }

    /** transient 取消计数（ReceiveAppService 的 cancelQuietly(TRANSIENT) 调用） */
    public void noteTransientCancel(long templateId) {
        if (!auxReady()) {
            return;
        }
        try {
            String key = TRANSIENT_WINDOW_PREFIX + "{" + templateId + "}";
            Long c = aux().opsForValue().increment(key);
            if (c != null && c == 1L) {
                aux().expire(key, Duration.ofSeconds(300));
            }
        } catch (RuntimeException ignore) {
        }
    }

    private boolean underMaintenance(long templateId) {
        if (!auxReady()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(aux().hasKey(MAINT_PREFIX + "{" + templateId + "}"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Long forceCount(long templateId) {
        if (!auxReady()) {
            return null;   // 无计数=不熔断（fail-open）
        }
        String v = aux().opsForValue().get(FORCE_COUNT_PREFIX + "{" + templateId + "}");
        return v == null ? null : Long.parseLong(v);
    }

    private void bumpForceCount(long templateId) {
        if (!auxReady()) {
            return;
        }
        try {
            String key = FORCE_COUNT_PREFIX + "{" + templateId + "}";
            Long c = aux().opsForValue().increment(key);
            if (c != null && c == 1L) {
                aux().expire(key, Duration.ofHours(24));
            }
        } catch (RuntimeException ignore) {
        }
    }

    /** 升级：24h 内 ≥5 条（任意 action）——REPAIRED/SHADOW/ESCALATE 豁免限频（§4） */
    private void escalateIfRepeated(long templateId) {
        long recent = driftLog.countSince(templateId, LocalDateTime.now().minusHours(24));
        if (recent >= 5) {
            log.error("ESCALATE: templateId={} 24h 内第 {} 条漂移记录——漂移源未消除，需人工排查根因",
                    templateId, recent);
        }
    }

    // ==================== 漂移落表（v2 修序：lastLogAt 在 insert 成功之后） ====================

    private void recordDrift(DriftEvent e) {
        long now = System.currentTimeMillis();
        Long last = lastLogAt.get(e.templateId());
        boolean exempt = "REPAIRED".equals(e.action()) || "SHADOW_REPAIR".equals(e.action())
                || "ESCALATE".equals(e.action());
        if (!exempt && last != null && now - last < logMinIntervalMs) {
            return;
        }
        try {
            driftLog.record(e);
            lastLogAt.put(e.templateId(), now);   // 修序：insert 成功后才占限频窗（DB 故障期不烧窗）
        } catch (RuntimeException ex) {
            log.error("drift log write failed (must not block reconcile): {}", e, ex);
        }
    }

    /** L4 专用落表（不限频） */
    private void recordDriftDirect(DriftEvent e) {
        try {
            driftLog.record(e);
        } catch (RuntimeException ex) {
            log.error("cross-check drift record failed (must not block audit): {}", e, ex);
        }
    }

    // ==================== 依赖注入辅助（redis 原语独立于业务 Lua） ====================

    // 四个辅助用途共用注入的 template（心跳/计数/维护标记/修复环计数——独立于业务 Lua，
    // 但不需要各自连接：直接复用同一个 bean）
    private org.springframework.data.redis.core.StringRedisTemplate auxRedis;

    @org.springframework.beans.factory.annotation.Autowired
    void wireAuxRedis(org.springframework.data.redis.core.StringRedisTemplate shared) {
        this.auxRedis = shared;
    }

    private org.springframework.data.redis.core.StringRedisTemplate aux() {
        return auxRedis;
    }

    /** 安全件全部对无 Redis 容错（单测无注入/Redis 故障——fail-open，不阻断对账主流程） */
    private boolean auxReady() {
        return auxRedis != null;
    }
}
