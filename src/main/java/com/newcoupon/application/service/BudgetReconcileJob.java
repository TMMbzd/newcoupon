package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetAccountMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetLedgerMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponBudgetReservationMapper;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetAccountPO;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetLedgerPO;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预算与发放对账任务（V6 阶段 1C）。四条等式，任何一条不成立 = 有变更逃出了
 * 账本原子配对（崩溃窗口/人工改库/代码 bug）——只告警不修复（预算是钱，
 * 自动修复钱账的风险大于收益，差异先留痕人工判）：
 * <ol>
 *   <li><b>账本=账户</b>：每账户末条流水的 (reserved,consumed) 快照 == 账户行现值；
 *       流水在账户行锁内成对写入，不等即说明有人绕过流水直改了账户；</li>
 *   <li><b>开口守恒</b>：每账户未终态预占合计 (amount-confirmed) == account.reserved；
 *       不等即预占行与账户余额脱钩（漏回池/漏占）；</li>
 *   <li><b>码池守恒</b>（V41 语义）：每批次 COUNT(码 USED)==batch.used 且
 *       COUNT(USED+LOCKED)<=total——used 计数与码行状态互证；</li>
 *   <li><b>任务守恒</b>（V39 语义）：grant_task total==success+fail+skip+pending
 *       （复用既有不变量，这里只是把它纳入周期巡检的视野）。</li>
 * </ol>
 * 差异写 {@code stock_drift_log}（action=BUDGET_RECONCILE_*，复用既有告警通道）
 * + ERROR 日志 + coupon_budget_reconcile_total 指标。
 *
 * <p>默认关（{@code coupon.budget.reconcile.enabled}，照 GrantTaskWorker 的开关纪律）；
 * @Scheduled 1h 为降级通道，XXL-JOB 名 couponBudgetReconcileJob；Redis 扫描锁
 * 保证多实例单飞——正确性不依赖锁（对账只读，重复跑无害）。
 */
@Component
@ConditionalOnProperty(prefix = "coupon.budget.reconcile", name = "enabled", havingValue = "true")
public class BudgetReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(BudgetReconcileJob.class);
    private static final String LOCK_KEY = "c:lock:budget-reconcile";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final CouponBudgetAccountMapper accountMapper;
    private final CouponBudgetLedgerMapper ledgerMapper;
    private final CouponBudgetReservationMapper reservationMapper;
    private final StockDriftLogGateway driftLog;
    private final RedisScanLock scanLock;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;
    private final int codeBatchLimit;

    public BudgetReconcileJob(CouponBudgetAccountMapper accountMapper,
                              CouponBudgetLedgerMapper ledgerMapper,
                              CouponBudgetReservationMapper reservationMapper,
                              StockDriftLogGateway driftLog,
                              RedisScanLock scanLock,
                              JdbcTemplate jdbc,
                              MeterRegistry meterRegistry,
                              @Value("${coupon.budget.reconcile.code-batch-limit:1000}") int codeBatchLimit) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.reservationMapper = reservationMapper;
        this.driftLog = driftLog;
        this.scanLock = scanLock;
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
        this.codeBatchLimit = codeBatchLimit;
    }

    /**
     * 单轮对账：四条等式各查一遍。
     * @return 发现的差异条数（0=全对）；-1=本轮异常（下一轮重试）
     */
    @XxlJob("couponBudgetReconcileJob")
    @Scheduled(fixedDelayString = "${coupon.budget.reconcile.fixed-delay-ms:3600000}",
            initialDelayString = "${coupon.budget.reconcile.initial-delay-ms:3600000}")
    public int runOnce() {
        String owner = "budget-reconcile-" + UUID.randomUUID();
        if (!scanLock.tryLock(LOCK_KEY, owner, LOCK_TTL)) {
            return 0;
        }
        try {
            int drifts = 0;
            drifts += checkLedgerMatchesAccounts();
            drifts += checkOpenReservations();
            drifts += checkCodePools();
            drifts += checkGrantTaskConservation();
            drifts += checkConsumedVsIssued();
            if (drifts == 0) {
                metric("ok");
            }
            return drifts;
        } catch (RuntimeException e) {
            log.error("budget reconcile round failed (next round retries)", e);
            metric("round_error");
            return -1;
        } finally {
            scanLock.unlock(LOCK_KEY, owner);
        }
    }

    /** 等式①：每账户末条流水快照 vs 账户行。 */
    private static boolean ledgerMismatch(CouponBudgetAccountPO account, CouponBudgetLedgerPO last) {
        return last.getReservedAfter().longValue() != account.getReserved().longValue()
                || last.getConsumedAfter().longValue() != account.getConsumed().longValue();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int checkLedgerMatchesAccounts() {
        int drifts = 0;
        for (CouponBudgetAccountPO account : accountMapper.selectAll()) {
            CouponBudgetLedgerPO last = ledgerMapper.selectLastByAccount(account.getId());
            if (last == null) {
                // 账户存在但零流水：reserved/consumed 必须都是 0，否则钱是无中生有来的
                if (account.getReserved() != 0 || account.getConsumed() != 0) {
                    drift(account.getScopeId(), "BUDGET_RECONCILE_LEDGER", 0,
                            "accountId=" + account.getId() + " 无流水但 reserved="
                                    + account.getReserved() + " consumed=" + account.getConsumed());
                    drifts++;
                }
                continue;
            }
            // 拆箱比较：两侧都是装箱 Long，!= 是引用比较——金额超出 Long 缓存区
            // （>127 分，真实预算必然超过）时账账一致也会误报 drift（1A 同类缺陷复发）
            if (ledgerMismatch(account, last)) {
                // 两段式复核（沿用 C1StockReconcile 手法）：账户行读与流水读各自独立
                // 快照，并发 reserve/confirm 落在两读之间会造成瞬时假差异——
                // 等 200ms 重读两侧，仍不一致才是真漂移
                sleepQuietly(200);
                CouponBudgetAccountPO fresh = accountMapper.selectById(account.getId());
                CouponBudgetLedgerPO lastFresh = ledgerMapper.selectLastByAccount(account.getId());
                if (fresh == null || lastFresh == null || !ledgerMismatch(fresh, lastFresh)) {
                    continue;
                }
                drift(0, "BUDGET_RECONCILE_LEDGER",
                        fresh.getReserved() - lastFresh.getReservedAfter(),
                        "accountId=" + account.getId()
                                + " 末条流水快照(reserved=" + lastFresh.getReservedAfter()
                                + ",consumed=" + lastFresh.getConsumedAfter() + ") != 账户行(reserved="
                                + fresh.getReserved() + ",consumed=" + fresh.getConsumed() + ")");
                drifts++;
            }
        }
        return drifts;
    }

    /** 等式②：每账户开口预占合计 vs reserved。 */
    private int checkOpenReservations() {
        int drifts = 0;
        for (CouponBudgetAccountPO account : accountMapper.selectAll()) {
            long openSum = reservationMapper.sumOpenByAccount(account.getId());
            if (openSum != account.getReserved()) {
                // 两段式复核：见 checkLedgerMatchesAccounts——并发结算与两读交错的假差异
                sleepQuietly(200);
                long openSumFresh = reservationMapper.sumOpenByAccount(account.getId());
                CouponBudgetAccountPO fresh = accountMapper.selectById(account.getId());
                if (fresh == null || openSumFresh == fresh.getReserved()) {
                    continue;
                }
                drift(0, "BUDGET_RECONCILE_OPEN",
                        fresh.getReserved() - openSumFresh,
                        "accountId=" + fresh.getId() + " 开口预占合计=" + openSumFresh
                                + " != account.reserved=" + fresh.getReserved());
                drifts++;
            }
        }
        return drifts;
    }

    /** 等式③：码池——每批次 used 计数与码行互证、USED+LOCKED 不超 total。 */
    private int checkCodePools() {
        // JOIN 一条查全（批次量=运营建批数，limit 兜底防大库慢查询）；分片外的单表 JOIN
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.id, b.batch_no, b.template_id, b.total, b.used, "
                        + "COALESCE(SUM(c.status = 2), 0) AS used_rows, "
                        + "COALESCE(SUM(c.status IN (1, 2)), 0) AS used_or_locked "
                        + "FROM coupon_code_batch b LEFT JOIN coupon_code c ON c.batch_id = b.id "
                        + "GROUP BY b.id, b.batch_no, b.template_id, b.total, b.used "
                        + "LIMIT " + codeBatchLimit);
        int drifts = 0;
        for (Map<String, Object> row : rows) {
            long used = num(row.get("used"));
            long usedRows = num(row.get("used_rows"));
            long usedOrLocked = num(row.get("used_or_locked"));
            long total = num(row.get("total"));
            if (used != usedRows || usedOrLocked > total) {
                drift(num(row.get("template_id")), "BUDGET_RECONCILE_CODE_POOL",
                        used - usedRows,
                        "batchNo=" + row.get("batch_no") + " used=" + used
                                + " 码行USED=" + usedRows + " USED+LOCKED=" + usedOrLocked
                                + " total=" + total);
                drifts++;
            }
        }
        return drifts;
    }

    /** 等式④：grant_task 计数守恒（total=success+fail+skip+pending）。 */
    private int checkGrantTaskConservation() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, task_no, template_id, total_count, success_count, fail_count, "
                        + "skip_count, pending_count FROM coupon_grant_task "
                        + "WHERE success_count + fail_count + skip_count + pending_count != total_count");
        for (Map<String, Object> row : rows) {
            long total = num(row.get("total_count"));
            long sum = num(row.get("success_count")) + num(row.get("fail_count"))
                    + num(row.get("skip_count")) + num(row.get("pending_count"));
            drift(num(row.get("template_id")), "BUDGET_RECONCILE_GRANT_TASK", total - sum,
                    "taskNo=" + row.get("task_no") + " total=" + total + " 四项合计=" + sum);
        }
        return rows.size();
    }

    /** 差异三连：drift log（复用既有告警通道）+ ERROR 日志 + 指标。落库失败不阻断其余检查。 */
    private void drift(long templateId, String action, long delta, String detail) {
        log.error("budget reconcile drift: action={}, delta={}, {}", action, delta, detail);
        metric(action);
        try {
            driftLog.record(new StockDriftLogGateway.DriftEvent(
                    templateId, 0, 0, 0, 0, 0, null, null, delta, action, detail));
        } catch (RuntimeException e) {
            log.error("budget reconcile drift log failed: action={}", action, e);
        }
    }

    private void metric(String result) {
        try {
            meterRegistry.counter("coupon_budget_reconcile_total", "result", result).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不能影响对账
        }
    }

    private static long num(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
    /**
     * 等式⑤：每预算账户 consumed vs 该活动全部模板已发券 Σbudget_unit_cost。
     * 崩窗（发券成功、结算未到）由僵尸回收把额度退回池——consumed 少记、可用额度
     * 虚增，而等式①-④全是账本内部自洽、对此结构性失明；这条把钱账拉到实发券面上。
     * 已发数取 coupon_stock_shard.Σused（C1 库存对账等式②锚定过 Σused=券行数的口径）。
     * 预算关掉的活动账本行不会出现（懒建只在开着时发生），无需额外过滤。
     */
    private int checkConsumedVsIssued() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.id AS account_id, a.scope_id AS activity_id, a.consumed AS consumed, "
                        + "IFNULL(SUM(t.budget_unit_cost * s.u), 0) AS issued_cost "
                        + "FROM coupon_budget_account a "
                        + "JOIN coupon_template t ON t.activity_id = a.scope_id "
                        + "JOIN (SELECT template_id, SUM(used) AS u FROM coupon_stock_shard "
                        + "      GROUP BY template_id) s ON s.template_id = t.id "
                        + "WHERE a.scope_type = 1 "
                        + "GROUP BY a.id, a.scope_id, a.consumed "
                        + "HAVING a.consumed != issued_cost");
        int drifts = 0;
        for (Map<String, Object> row : rows) {
            drift(0, "BUDGET_RECONCILE_CONSUMED_VS_ISSUED",
                    num(row.get("consumed")) - num(row.get("issued_cost")),
                    "accountId=" + row.get("account_id") + " activityId=" + row.get("activity_id")
                            + " consumed=" + row.get("consumed")
                            + " != 已发券Σ成本=" + row.get("issued_cost"));
            drifts++;
        }
        return drifts;
    }

}
