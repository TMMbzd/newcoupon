package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * gate 与限领计数的周期对账（讨论 02 核对结论 P4）。
 *
 * <p>要修的问题：四个键（gate/ul/uld/dly）"偏低但不为 0"的漂移没有任何错误码能发现——
 * 偏高会触发限领拒绝码、被同请求修复；偏低只表现为多受理被 DB 拒（浪费）或 gate 假售罄（少卖），
 * 此前只能等键 TTL（ul 7 天、日限 48 小时）。本任务把静默偏差的收敛窗口压到小时级。
 *
 * <p><b>对账等式</b>（与请求路径修复共用同一份计算，见 {@link GateCounterRepairService}
 * 与 {@link GateRebuildService}，避免公式漂移）：
 * <pre>
 *   gate = max(0, Σquota − Σused − countInFlight)        （同一事务快照，GateRebuildTxService）
 *   ul   = COUNT(user_coupon) + countInFlight(user,tpl)
 *   uld  = coupon_user_daily_counter.used + countInFlight(user,tpl,day)
 *   dly  = coupon_daily_counter.used + countInFlight(tpl,day)
 * </pre>
 *
 * <p><b>读序</b>：先 GET Redis 当前值、再查 DB 目标。并发受理对两边同向 +1（cur 加一、
 * 在途任务加一），diff 不变——窗口噪声只剩罕见的释放/修复动作，因此阈值默认 0（任何偏差都修）。
 * 反过来先查 DB 再 GET，会把窗口内每个受理都算成 diff。
 *
 * <p><b>只枚举存在的键</b>（SCAN）：不存在的键在下次访问时由"键缺失"可修复码自愈，
 * 主动补建历史键是浪费。uld/dly 只对账今天——往日键不会再有新写入，等 TTL 自然过期。
 *
 * <p><b>不做的事</b>：不动 seq（只增不减，修低会造成序号碰撞）；不动 idem（请求生命周期键）。
 * 修复写回沿用修复路径的无条件 SET——覆盖竞态与请求路径同类（偏差有界、向 DB 收敛）；
 * 单实例由 Redis 扫描锁保证，不与请求路径抢单飞许可（对账低频批量，许可留给请求路径防击穿）。
 */
@Component
public class GateCounterReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(GateCounterReconcileJob.class);
    private static final String LOCK_KEY = "c:issue:gate-reconcile:lock";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private static final Pattern GATE_KEY = Pattern.compile("^c:issue:gate:\\{(\\d+)\\}$");
    private static final Pattern UL_KEY = Pattern.compile("^c:issue:ul:\\{(\\d+)\\}:(\\d+)$");
    private static final Pattern ULD_KEY =
            Pattern.compile("^c:issue:uld:\\{(\\d+)\\}:(\\d+):(\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern DLY_KEY =
            Pattern.compile("^c:issue:dly:\\{(\\d+)\\}:(\\d{4}-\\d{2}-\\d{2})$");

    /** 损坏值哨兵：与任何目标的差都超阈值，直接按目标重写。 */
    private static final long CORRUPT = Long.MIN_VALUE;

    private final StringRedisTemplate redis;
    private final RedisIssueGate issueGate;
    private final GateCounterRepairService counterRepairService;
    private final GateRebuildTxService gateRebuildTxService;
    private final GateRebuildService gateRebuildService;
    private final StockDriftLogGateway driftLog;
    private final RedisScanLock scanLock;
    private final MeterRegistry meterRegistry;
    private final int threshold;
    private final long scanCount;
    private final long maxRoundMs;
    private final long idemTtlSeconds;
    private final long dayTtlSeconds;

    public GateCounterReconcileJob(StringRedisTemplate redis,
                                   RedisIssueGate issueGate,
                                   GateCounterRepairService counterRepairService,
                                   GateRebuildTxService gateRebuildTxService,
                                   GateRebuildService gateRebuildService,
                                   StockDriftLogGateway driftLog,
                                   RedisScanLock scanLock,
                                   MeterRegistry meterRegistry,
                                   @Value("${coupon.issue.gate-reconcile.threshold:0}") int threshold,
                                   @Value("${coupon.issue.gate-reconcile.scan-count:500}") long scanCount,
                                   @Value("${coupon.issue.gate-reconcile.max-round-ms:300000}") long maxRoundMs,
                                   @Value("${coupon.issue.idem-ttl-seconds:604800}") long idemTtlSeconds,
                                   @Value("${coupon.issue.day-ttl-seconds:172800}") long dayTtlSeconds) {
        this.redis = redis;
        this.issueGate = issueGate;
        this.counterRepairService = counterRepairService;
        this.gateRebuildTxService = gateRebuildTxService;
        this.gateRebuildService = gateRebuildService;
        this.driftLog = driftLog;
        this.scanLock = scanLock;
        this.meterRegistry = meterRegistry;
        this.threshold = threshold;
        this.scanCount = scanCount;
        this.maxRoundMs = maxRoundMs;
        this.idemTtlSeconds = idemTtlSeconds;
        this.dayTtlSeconds = dayTtlSeconds;
    }

    /**
     * 单轮对账：四个键族各扫一遍。@Scheduled 为降级通道；XXL-JOB 名
     * couponIssueGateReconcileJob（需在调度中心注册；扫描锁保证单实例，路由策略任意）。
     *
     * <p>轮内预算（默认 5 分钟，须小于扫描锁 TTL 10 分钟）：键空间无上界（ul 键带 7 天
     * TTL，数量随一周活跃用户线性增长），固定锁 TTL 配无界轮会在大键空间下锁过期、
     * 双实例并发。预算耗尽本轮截断（下轮重扫），不视为异常。
     *
     * @return 本轮修复的键数；-1=本轮异常（下一轮重试）
     */
    @XxlJob("couponIssueGateReconcileJob")
    @Scheduled(fixedDelayString = "${coupon.issue.gate-reconcile.fixed-delay-ms:3600000}",
            initialDelayString = "${coupon.issue.gate-reconcile.initial-delay-ms:3600000}")
    public int runOnce() {
        String owner = "gate-reconcile-" + UUID.randomUUID();
        if (!scanLock.tryLock(LOCK_KEY, owner, LOCK_TTL)) {
            return 0;
        }
        long deadline = System.currentTimeMillis() + maxRoundMs;
        java.util.concurrent.atomic.AtomicInteger fixed = new java.util.concurrent.atomic.AtomicInteger();
        try {
            scanKeys("c:issue:gate:*", deadline, k -> { if (reconcileGateKey(k)) fixed.incrementAndGet(); });
            scanKeys("c:issue:ul:*", deadline, k -> { if (reconcileUserTotalKey(k)) fixed.incrementAndGet(); });
            scanKeys("c:issue:uld:*", deadline, k -> { if (reconcileUserDailyKey(k)) fixed.incrementAndGet(); });
            scanKeys("c:issue:dly:*", deadline, k -> { if (reconcileTemplateDailyKey(k)) fixed.incrementAndGet(); });
            return fixed.get();
        } catch (Exception e) {
            log.error("gate counter reconcile round failed (next round retries)", e);
            return -1;
        } finally {
            scanLock.unlock(LOCK_KEY, owner);
        }
    }

    /** 单键处理连续失败到这个数就放弃本键族（视为系统性故障，避免逐键撞错拖满预算）。 */
    private static final int CONSECUTIVE_FAILURE_LIMIT = 20;

    /**
     * SCAN 一个键族，逐键回调。隔离规则（对账评审修正）：单个键的处理异常（键名畸形、
     * 该键 DB/Redis 失败）只跳过该键并计数，不中断族；连续失败超限或 SCAN 本身失败才
     * 中断本族并向外抛出，让 runOnce 返回 -1——否则一个毒键（格式合法但日期非法）会
     * 每轮卡死四分之一的对账，监控却看到"成功"。
     */
    private void scanKeys(String pattern, long deadline, Consumer<String> handler) {
        int consecutiveFailures = 0;
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(pattern).count(scanCount).build())) {
            while (cursor.hasNext()) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("gate reconcile round budget exhausted, truncating: pattern={}", pattern);
                    metric("budget_exhausted");
                    return;
                }
                String key = cursor.next();
                try {
                    handler.accept(key);
                    consecutiveFailures = 0;
                } catch (RuntimeException e) {
                    consecutiveFailures++;
                    metric("key_error");
                    log.warn("gate reconcile skipped one key ({} of {} consecutive): key={}",
                            consecutiveFailures, CONSECUTIVE_FAILURE_LIMIT, key, e);
                    if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                        throw new IllegalStateException(
                                "gate reconcile consecutive key failures reached limit: pattern="
                                        + pattern, e);
                    }
                }
            }
        } catch (RuntimeException e) {
            metric("scan_error");
            throw e;   // 让 runOnce 感知截断/失败并返回 -1，而不是静默吞掉
        }
    }

    // ------------------------------------------------------------------
    // 四个键族的对账逻辑。包级可见以便单测直接按 key 驱动。
    // ------------------------------------------------------------------

    boolean reconcileGateKey(String key) {
        Matcher m = GATE_KEY.matcher(key);
        if (!m.matches()) {
            return false;
        }
        long templateId = Long.parseLong(m.group(1));
        Long cur = readCounter(key);
        if (cur == null) {
            return false;   // SCAN 与 GET 之间过期：缺失键由访问路径自愈
        }
        // 读序：先 GET 再算目标（与计数键同一约定）。检查阶段只算不写，
        // 超阈值才 rebuild(force=true)——直接复用重建（公式/TTL/抖动同源）。
        long expected = gateRebuildTxService.computeRemaining(templateId);
        // gate 的容差至少 1：受理的 Lua 扣减与任务落库提交之间有毫秒级窗口，
        // 高 QPS 下几乎每轮 GET 都会撞上 ±1 的瞬态差；±1 属准入竞态噪声
        // （多受理一张会被 DB 拒），修它只会制造漂移记录噪音。持续更大的偏差照修。
        int gateTolerance = Math.max(threshold, 1);
        long diff = (cur == CORRUPT) ? expected : expected - cur;
        if (cur != CORRUPT && Math.abs(diff) <= gateTolerance) {
            metric("checked");
            return false;
        }
        long written = gateRebuildService.rebuild(templateId, true);
        // 漂移记录用实际写入值（rebuild 的第二个快照）与损坏安全的 delta（损坏时=写入值）
        drift("gate_reconcile_fix", templateId, key, cur, written,
                (cur == CORRUPT) ? written : written - cur);
        metric("fixed_gate");
        log.warn("gate reconcile fixed: templateId={}, {} -> {}", templateId, cur, written);
        return true;
    }

    boolean reconcileUserTotalKey(String key) {
        Matcher m = UL_KEY.matcher(key);
        if (!m.matches()) {
            return false;
        }
        long templateId = Long.parseLong(m.group(1));
        long userId = Long.parseLong(m.group(2));
        Long cur = readCounter(key);
        if (cur == null) {
            return false;
        }
        long target = counterRepairService.computeUserTotalTarget(userId, templateId);
        return fixCounter("ul_reconcile_fix", key, templateId, cur, target,
                () -> issueGate.repairUserTotal(templateId, userId, target, idemTtlSeconds));
    }

    boolean reconcileUserDailyKey(String key) {
        Matcher m = ULD_KEY.matcher(key);
        if (!m.matches()) {
            return false;
        }
        long templateId = Long.parseLong(m.group(1));
        long userId = Long.parseLong(m.group(2));
        LocalDate day = LocalDate.parse(m.group(3));
        if (!day.equals(LocalDate.now())) {
            return false;   // 往日键不再有新写入，等 TTL
        }
        Long cur = readCounter(key);
        if (cur == null) {
            return false;
        }
        long target = counterRepairService.computeUserDailyTarget(userId, templateId, day);
        return fixCounter("uld_reconcile_fix", key, templateId, cur, target,
                () -> issueGate.repairUserDaily(templateId, userId, day, target, dayTtlSeconds));
    }

    boolean reconcileTemplateDailyKey(String key) {
        Matcher m = DLY_KEY.matcher(key);
        if (!m.matches()) {
            return false;
        }
        long templateId = Long.parseLong(m.group(1));
        LocalDate day = LocalDate.parse(m.group(2));
        if (!day.equals(LocalDate.now())) {
            return false;
        }
        Long cur = readCounter(key);
        if (cur == null) {
            return false;
        }
        long target = counterRepairService.computeTemplateDailyTarget(templateId, day);
        return fixCounter("dly_reconcile_fix", key, templateId, cur, target,
                () -> issueGate.repairTemplateDaily(templateId, day, target, dayTtlSeconds));
    }

    // ------------------------------------------------------------------

    /** 差值超过阈值才修；损坏值（哨兵）直接修。读序约定：cur 在 target 之前读（见类注释）。
     *  漂移记录的 delta 在损坏值时取 target 本身——哨兵参与减法会溢出成天文数字写进审计表。 */
    private boolean fixCounter(String action, String key, long templateId,
                               long cur, long target, Runnable repair) {
        long diff = (cur == CORRUPT) ? target : target - cur;
        if (cur != CORRUPT && Math.abs(diff) <= threshold) {
            metric("checked");
            return false;
        }
        repair.run();
        drift(action, templateId, key, cur, target, diff);
        metric("fixed_" + action);
        log.warn("counter reconcile fixed: key={}, {} -> {}", key, cur, target);
        return true;
    }

    /** 读当前值：缺失返回 null（跳过，访问路径自愈）；读失败返回 null（本轮跳过）；
     *  非数字返回 CORRUPT 哨兵（按目标重写）。 */
    private Long readCounter(String key) {
        String raw;
        try {
            raw = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.debug("counter reconcile read failed: key={}", key, e);
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return CORRUPT;
        }
    }

    private void drift(String action, long templateId, String key, long cur, long target, long diff) {
        try {
            driftLog.record(new StockDriftLogGateway.DriftEvent(
                    templateId, 0, 0, 0, 0, 0, null,
                    cur, diff, action,
                    "key=" + key + " redis=" + cur + " target=" + target,
                    null, null));
        } catch (RuntimeException e) {
            log.error("counter reconcile drift log failed: key={}", key, e);
        }
    }

    private void metric(String result) {
        try {
            meterRegistry.counter("issue_gate_reconcile_total", "result", result).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不能影响对账
        }
    }
}
