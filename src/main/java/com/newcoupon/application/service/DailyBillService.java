package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponSettlePartyDailyBillMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponSettlementHoldMapper;
import com.newcoupon.infrastructure.persistence.po.CouponDailyBillPO;
import com.newcoupon.infrastructure.persistence.po.CouponSettlePartyDailyBillPO;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * T+1 日账单：按模板聚合当日账目（净额），落 coupon_daily_bill。
 *
 * <p>聚合源：`coupon_share_detail` 的正分账行（核销）减去 `coupon_refund_detail`
 * 的退款明细（冲回），并兼容旧版写在 `coupon_share_detail` 里的负行。
 * 两张表都只增不改，账单行 upsert——重跑任意多次结果一致，因此正确性不依赖调度锁。
 * 多实例仍加 RedisScanLock：一天的聚合虽轻，重复执行会双倍扫描分账/退款表，
 * 并发窗口还可能让较旧的聚合结果覆盖较新的结果；锁让重跑串行、让路可观测。
 *
 * <p>计数语义（2026-10-18 起）：`confirm_count` = 正分账行数（一个多行订单会大于 1），
 * `refund_count` = 退款明细行数（一次退多行会大于 1）。需要“核销笔数”时按 usage_no 去重另算。
 *
 * <p>结算语义：退款明细按 create_time 归入退款日的账单——当日退款当日净额归零，
 * 跨日退款（T+7 售后）在退款日记一笔负数，财务在账单上能单独看到冲回。
 *
 * <p>可结算口径（V47，3min 结算最小版）：两张账单聚合都只取 settle_eligible=1——
 * 质量排除行（商家钱落 __default__/出资不明，见 SettleQualityFlags）不进真实账单；
 * 主体账单另记 ineligible_*_count 与 hold/payable（应付=净额-ACTIVE hold，负数如实展示）。
 */
@Service
public class DailyBillService {

    private static final Logger log = LoggerFactory.getLogger(DailyBillService.class);

    /** 账单日切固定 Asia/Shanghai，不跟随 JVM 默认时区 */
    private static final ZoneId BILL_ZONE = ZoneId.of("Asia/Shanghai");

    /** 多实例调度互斥键：一天一轮，锁 TTL 远大于最坏聚合时长（RedisScanLock 不续期） */
    private static final String LOCK_KEY = "c:lock:daily-bill";
    private static final Duration LOCK_TTL = Duration.ofMinutes(60);

    private final CouponDailyBillMapper billMapper;
    private final CouponSettlePartyDailyBillMapper settlePartyBillMapper;
    private final CouponSettlementHoldMapper holdMapper;
    private final StockDriftLogGateway driftLog;
    private final TransactionTemplate transactionTemplate;
    /** 主体账单开关：默认开（纯增量新表）；关闭后只出旧模板账单，便于回滚 */
    private final boolean settlePartyBillEnabled;
    private final RedisScanLock scanLock;
    private final MeterRegistry meterRegistry;

    public DailyBillService(CouponDailyBillMapper billMapper,
                            CouponSettlePartyDailyBillMapper settlePartyBillMapper,
                            CouponSettlementHoldMapper holdMapper,
                            StockDriftLogGateway driftLog,
                            PlatformTransactionManager transactionManager,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${coupon.billing.settle-party.enabled:true}")
                            boolean settlePartyBillEnabled,
                            RedisScanLock scanLock,
                            MeterRegistry meterRegistry) {
        this.billMapper = billMapper;
        this.settlePartyBillMapper = settlePartyBillMapper;
        this.holdMapper = holdMapper;
        this.driftLog = driftLog;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.settlePartyBillEnabled = settlePartyBillEnabled;
        this.scanLock = scanLock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 生成指定日期的账单（幂等，可重跑/补数）。返回聚合出的模板行数。
     *
     * <p><b>沙箱过滤（V6 2B）</b>：聚合 SQL 里 LEFT JOIN open_app 只取 PROD 租户的行
     * （沙箱核销不进真账单）；被跳过的沙箱行数不单独统计——想知道沙箱用量走
     * 开放面审计（open_api_audit），账单这条管道只对真钱负责。
     * {@link #generateSettlePartyBill} 用同一过滤条件，保证 reconcile 等式两侧口径一致。
     */
    public int generateBill(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        List<CouponDailyBillMapper.DailyAgg> rows = billMapper.aggregateByDate(start, end);
        for (CouponDailyBillMapper.DailyAgg row : rows) {
            CouponDailyBillPO bill = new CouponDailyBillPO();
            bill.setBillDate(date);
            bill.setTemplateId(row.getTemplateId());
            bill.setConfirmCount(row.getConfirmCount().intValue());
            bill.setRefundCount(row.getRefundCount().intValue());
            bill.setDiscountTotal(row.getDiscountTotal());
            bill.setPlatformBurden(row.getPlatformBurden());
            bill.setMerchantBurden(row.getMerchantBurden());
            billMapper.upsertBill(bill);
        }
        log.info("DAILY_BILL date={} templates={} 核销行={} 退款行={}（净额已含冲回）",
                date, rows.size(),
                rows.stream().mapToLong(CouponDailyBillMapper.DailyAgg::getConfirmCount).sum(),
                rows.stream().mapToLong(CouponDailyBillMapper.DailyAgg::getRefundCount).sum());
        return rows.size();
    }

    /**
     * 按结算主体生成指定日期的账单（V24 环 3；V47 起带 hold/payable/质量桶）。
     * 与模板账单同源：正分账行计核销，退款明细/旧负分账行计冲回，**两张账单都只取
     * settle_eligible=1**（可结算口径，V47）——质量排除行两边同步不进，reconcile 等式不破。
     * 唯一键 `(日期,appId,主体,模板)` 幂等覆盖。
     *
     * <p><b>hold 扣减（V47）</b>：按 (appId, 主体) 汇总 ACTIVE hold，模板级 hold 只扣对应
     * 模板行；**主体级 hold（template_id 为空）对该主体的每个模板行全额扣减**——
     * 主体跨多模板时合计扣减=行数×金额，会过度扣减；payable 允许为负并如实展示
     * （负数=该主体有未释放 hold 超过当期净额，财务需人工关注）。精确分摊/结转属
     * 3B 批次与结算单的职责（本刀裁剪，见 runbook）。
     *
     * @return 聚合出的主体行数
     */
    public int generateSettlePartyBill(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        List<CouponSettlePartyDailyBillMapper.SettlePartyAgg> rows =
                settlePartyBillMapper.aggregateByDate(start, end);
        // 质量桶与 hold 各一条聚合查询拿全量 map（生成循环里只查内存——不 N+1）
        Map<String, CouponSettlePartyDailyBillMapper.IneligibleAgg> ineligible =
                new HashMap<>();
        for (CouponSettlePartyDailyBillMapper.IneligibleAgg agg
                : settlePartyBillMapper.countIneligibleByDate(start, end)) {
            ineligible.put(aggKey(agg.getAppId(), agg.getSettlePartyId(), agg.getTemplateId()), agg);
        }
        Map<String, Long> partyLevelHold = new HashMap<>();
        Map<String, Long> templateHold = new HashMap<>();
        for (CouponSettlementHoldMapper.HoldAgg agg : holdMapper.sumActiveGrouped()) {
            if (agg.getTemplateId() == null) {
                partyLevelHold.merge(agg.getAppId() + '|' + agg.getSettlePartyId(),
                        agg.getTotalAmount(), Long::sum);
            } else {
                templateHold.merge(agg.getAppId() + '|' + agg.getSettlePartyId() + '|' + agg.getTemplateId(),
                        agg.getTotalAmount(), Long::sum);
            }
        }

        for (CouponSettlePartyDailyBillMapper.SettlePartyAgg row : rows) {
            CouponSettlePartyDailyBillPO bill = new CouponSettlePartyDailyBillPO();
            bill.setBillDate(date);
            bill.setAppId(row.getAppId());
            bill.setSettlePartyId(row.getSettlePartyId());
            bill.setTemplateId(row.getTemplateId());
            bill.setConfirmCount(row.getConfirmCount().intValue());
            bill.setRefundCount(row.getRefundCount().intValue());
            bill.setDiscountTotal(row.getDiscountTotal());
            bill.setPlatformBurden(row.getPlatformBurden());
            bill.setMerchantBurden(row.getMerchantBurden());

            // hold：主体级（全适用）+ 模板级（只扣本行）——金额口径见方法注释
            long hold = partyLevelHold.getOrDefault(
                    row.getAppId() + '|' + row.getSettlePartyId(), 0L)
                    + templateHold.getOrDefault(aggKey(row.getAppId(), row.getSettlePartyId(),
                    row.getTemplateId()), 0L);
            bill.setHoldAmount(hold);
            // payable = 分账净额(已含退款冲回) - hold；负数不钳制——钳制会掩盖"hold 超净额"这个事实本身
            bill.setPayableAmount(row.getDiscountTotal() - hold);

            CouponSettlePartyDailyBillMapper.IneligibleAgg bucket = ineligible.get(
                    aggKey(row.getAppId(), row.getSettlePartyId(), row.getTemplateId()));
            bill.setIneligibleShareCount(bucket == null ? 0 : bucket.getIneligibleShareCount().intValue());
            bill.setIneligibleRefundCount(bucket == null ? 0 : bucket.getIneligibleRefundCount().intValue());
            settlePartyBillMapper.upsertBill(bill);
        }
        log.info("SETTLE_PARTY_DAILY_BILL date={} parties/templates={} 核销行={} 退款行={}",
                date, rows.size(),
                rows.stream().mapToLong(CouponSettlePartyDailyBillMapper.SettlePartyAgg::getConfirmCount).sum(),
                rows.stream().mapToLong(CouponSettlePartyDailyBillMapper.SettlePartyAgg::getRefundCount).sum());
        return rows.size();
    }

    private static String aggKey(String appId, String partyId, Long templateId) {
        return appId + '|' + partyId + '|' + templateId;
    }

    /**
     * 生成模板账单 + 主体账单，最后对账。
     *
     * <p><b>两张账单各自一个事务</b>：新表失败不回滚旧模板账单（05 要求“旧模板账单不动、
     * 新表独立可回滚”）。两张账单同源 append-only，正常无并发写时合计守恒；
     * 若两段之间恰有退款提交，`reconcile` 会留下差异日志，重跑即可收敛。
     */
    public void generateAll(LocalDate date) {
        transactionTemplate.executeWithoutResult(status -> generateBill(date));
        if (!settlePartyBillEnabled) {
            log.info("SETTLE_PARTY_BILL_DISABLED date={}（只出模板账单；新表可整体回滚）", date);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> generateSettlePartyBill(date));
        int mismatches = reconcile(date);
        if (mismatches > 0) {
            try {
                meterRegistry.counter("daily_bill_reconcile_mismatch_total").increment(mismatches);
            } catch (RuntimeException ignore) {
                // 指标失败不影响账单
            }
        }
    }

    /**
     * 对账：主体账单按模板汇总必须等于模板账单（金额/行数四项）。
     * 返回不一致的模板数，并逐条 ERROR 留现场；0 = 一致。
     *
     * <p>这是“多主体相加 = 模板账单”验收的运行时守卫，不只是一条测试断言。
     *
     * <p><b>差异落表+指标（V47）</b>：现状差异只打 ERROR 日志——日志会滚走、不可查询。
     * 起落 {@code stock_drift_log}（action=SETTLE_BILL_RECONCILE，detail 记两侧五项读数，
     * 复用预算对账的告警通道）+ 指标 {@code coupon_settle_reconcile_diff_total{type}}
     * （type 有界：PARTY_VS_TEMPLATE=金额/行数对不上 / PARTY_ORPHAN_TEMPLATE=主体有行模板没有）。
     * 落库失败不阻断对账主流程（与 StockDriftLogGateway 的使用约定一致）。
     */
    public int reconcile(LocalDate date) {
        Map<Long, CouponDailyBillPO> templates = new HashMap<>();
        for (CouponDailyBillPO bill : billMapper.selectByDate(date)) {
            templates.put(bill.getTemplateId(), bill);
        }
        Map<Long, long[]> sums = new HashMap<>();
        for (CouponSettlePartyDailyBillPO p : settlePartyBillMapper.selectAllByDate(date)) {
            long[] v = sums.computeIfAbsent(p.getTemplateId(), k -> new long[5]);
            v[0] += p.getConfirmCount();
            v[1] += p.getRefundCount();
            v[2] += p.getDiscountTotal();
            v[3] += p.getPlatformBurden();
            v[4] += p.getMerchantBurden();
        }

        int mismatches = 0;
        for (Map.Entry<Long, CouponDailyBillPO> e : templates.entrySet()) {
            long[] v = sums.getOrDefault(e.getKey(), new long[5]);
            CouponDailyBillPO t = e.getValue();
            if (v[0] != t.getConfirmCount() || v[1] != t.getRefundCount()
                    || v[2] != t.getDiscountTotal() || v[3] != t.getPlatformBurden()
                    || v[4] != t.getMerchantBurden()) {
                mismatches++;
                log.error("SETTLE_PARTY_BILL_RECONCILE_MISMATCH date={} templateId={} "
                                + "模板[核销={},退款={},优惠={},平台={},商家={}] "
                                + "主体合计[核销={},退款={},优惠={},平台={},商家={}]",
                        date, e.getKey(), t.getConfirmCount(), t.getRefundCount(),
                        t.getDiscountTotal(), t.getPlatformBurden(), t.getMerchantBurden(),
                        v[0], v[1], v[2], v[3], v[4]);
                recordReconcileDiff(date, e.getKey(), "PARTY_VS_TEMPLATE",
                        t.getConfirmCount(), t.getRefundCount(), t.getDiscountTotal(),
                        t.getPlatformBurden(), t.getMerchantBurden(), v);
            }
        }
        for (Long templateId : sums.keySet()) {
            if (!templates.containsKey(templateId)) {
                mismatches++;
                log.error("SETTLE_PARTY_BILL_RECONCILE_ORPHAN date={} templateId={} "
                        + "主体账单有行但模板账单没有", date, templateId);
                recordReconcileDiff(date, templateId, "PARTY_ORPHAN_TEMPLATE",
                        0, 0, 0, 0, 0, sums.get(templateId));
            }
        }
        if (mismatches == 0) {
            log.info("SETTLE_PARTY_BILL_RECONCILE_OK date={} templates={}", date, templates.size());
        }
        return mismatches;
    }

    /**
     * 差异三连：drift log（detail 是紧凑 JSON——两侧五项读数，512 列宽内）+ 指标。
     * 两步各自 try/catch：留痕失败不能让对账本身失败（对账的返回值驱动 mismatch 指标）。
     */
    private void recordReconcileDiff(LocalDate date, Long templateId, String type,
                                     long tConfirm, long tRefund, long tDiscount,
                                     long tPlatform, long tMerchant, long[] partySum) {
        String detail = String.format(
                "{\"date\":\"%s\",\"templateId\":%d,\"type\":\"%s\","
                        + "\"template\":{\"confirm\":%d,\"refund\":%d,\"discount\":%d,"
                        + "\"platform\":%d,\"merchant\":%d},"
                        + "\"partySum\":{\"confirm\":%d,\"refund\":%d,\"discount\":%d,"
                        + "\"platform\":%d,\"merchant\":%d}}",
                date, templateId, type, tConfirm, tRefund, tDiscount, tPlatform, tMerchant,
                partySum[0], partySum[1], partySum[2], partySum[3], partySum[4]);
        try {
            driftLog.record(new StockDriftLogGateway.DriftEvent(
                    templateId, 0, 0, 0, 0, 0, null, null, 1, "SETTLE_BILL_RECONCILE", detail));
        } catch (RuntimeException e) {
            log.error("SETTLE_BILL_RECONCILE drift log failed: date={} templateId={}",
                    date, templateId, e);
        }
        try {
            meterRegistry.counter("coupon_settle_reconcile_diff_total", "type", type).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不影响对账
        }
    }

    /**
     * 调度入口（@Scheduled 降级通道 / @XxlJob 生产通道共用）：抢 Redis 锁后生成指定日期账单。
     *
     * <p>返回：1=已生成；0=锁被其他实例持有，本轮让路；-1=失败（已记失败指标与日志）。
     * 锁只消多实例重复负载，账单本身幂等（upsert 覆盖）；Redis 不可用时**降级直接生成**——
     * T+1 账单一天只有一轮，错过比重复更贵。锁释放失败只打日志，不把已完成的账单判成失败。
     *
     * <p>手动补数不走本方法，直接调用 {@link #generateAll(LocalDate)}（不加锁）。
     */
    public int runOnce(LocalDate date) {
        String owner = "daily-bill-" + UUID.randomUUID();
        Boolean locked;
        try {
            locked = scanLock.tryLock(LOCK_KEY, owner, LOCK_TTL);
        } catch (RuntimeException e) {
            locked = null;
            metric("degraded");
            log.warn("DAILY_BILL_LOCK_DEGRADED date={} Redis 锁不可用，降级直接生成（账单幂等）",
                    date, e);
        }
        if (Boolean.FALSE.equals(locked)) {
            metric("skipped");
            log.debug("daily bill skipped: another instance holds {}", LOCK_KEY);
            return 0;
        }
        boolean acquired = Boolean.TRUE.equals(locked);
        long started = System.nanoTime();
        try {
            generateAll(date);
            metric("success");
            return 1;
        } catch (RuntimeException e) {
            metric("failed");
            log.error("DAILY_BILL_FAIL date={} 账单生成失败（次日定时重跑或手动补数）", date, e);
            return -1;
        } finally {
            if (acquired) {
                try {
                    scanLock.unlock(LOCK_KEY, owner);
                } catch (RuntimeException e) {
                    log.warn("daily bill lock release failed (TTL will expire): {}", LOCK_KEY, e);
                }
            }
            recordDuration(started);
        }
    }

    /**
     * 每天 00:30 出昨日账单（T+1，Asia/Shanghai）。幂等——错过/重跑由 generateAll 补。
     * 异常在事务边界外捕获：失败的那张账单已回滚，已提交的那张保留。
     */
    @Scheduled(cron = "0 30 0 * * ?", zone = "Asia/Shanghai")
    public void generateYesterdayBill() {
        try {
            runOnce(resolveBillDate(null));
        } catch (RuntimeException e) {
            metric("failed");
            log.error("DAILY_BILL_FAIL 昨日账单生成失败（次日定时重跑或手动补数）", e);
        }
    }

    /**
     * XXL-JOB 生产通道。任务参数可传 {@code yyyy-MM-dd} 补指定日期；留空=昨日。
     * 参数非法直接失败并留痕，绝不静默改成昨日（防止手误补错账）。
     */
    @XxlJob("couponDailyBillJob")
    public void xxlGenerateBill() {
        int result;
        try {
            result = runOnce(resolveBillDate(currentJobParam()));
        } catch (RuntimeException e) {
            metric("failed");
            log.error("DAILY_BILL_FAIL XXL 账单任务参数/执行失败", e);
            markXxlFailed(e.getMessage());
            return;
        }
        if (result < 0) {
            markXxlFailed("daily bill generation failed");
        }
    }

    /** XXL 上下文只在执行器线程存在；@Scheduled/测试/手动线程没有上下文，按昨日处理。 */
    private static String currentJobParam() {
        try {
            return XxlJobHelper.getJobParam();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LocalDate resolveBillDate(String param) {
        return parseBillDate(param, LocalDate.now(BILL_ZONE).minusDays(1));
    }

    /** 纯函数便于单测：空白=fallback（昨日），否则严格 {@code yyyy-MM-dd}。 */
    static LocalDate parseBillDate(String param, LocalDate fallback) {
        if (param == null || param.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(param.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("无效的账单日期参数（期望 yyyy-MM-dd）: " + param, e);
        }
    }

    private void metric(String result) {
        try {
            meterRegistry.counter("daily_bill_job_total", "result", result).increment();
        } catch (RuntimeException ignore) {
            // 指标失败不能影响账单
        }
    }

    private void recordDuration(long startedNanos) {
        try {
            meterRegistry.timer("daily_bill_job_duration_seconds")
                    .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignore) {
            // 指标失败不能影响账单
        }
    }

    private static void markXxlFailed(String message) {
        try {
            XxlJobHelper.handleFail(message);
        } catch (RuntimeException ignore) {
            // 非 XXL 调度线程（测试/手动）没有上下文
        }
    }
}
