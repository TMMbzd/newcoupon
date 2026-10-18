package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponSettlePartyDailyBillMapper;
import com.newcoupon.infrastructure.persistence.po.CouponDailyBillPO;
import com.newcoupon.infrastructure.persistence.po.CouponSettlePartyDailyBillPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T+1 日账单：按模板聚合当日账目（净额），落 coupon_daily_bill。
 *
 * <p>聚合源：`coupon_share_detail` 的正分账行（核销）减去 `coupon_refund_detail`
 * 的退款明细（冲回），并兼容旧版写在 `coupon_share_detail` 里的负行。
 * 两张表都只增不改，账单行 upsert——重跑任意多次结果一致。因此多实例重复触发也无害
 * （与过期扫描不同，不需要 Redis 抢锁：那里消的是重复扫描的 DB 负载，
 * 这里一天一次的聚合查询不值得加锁复杂度）。
 *
 * <p>计数语义（2026-10-18 起）：`confirm_count` = 正分账行数（一个多行订单会大于 1），
 * `refund_count` = 退款明细行数（一次退多行会大于 1）。需要“核销笔数”时按 usage_no 去重另算。
 *
 * <p>结算语义：退款明细按 create_time 归入退款日的账单——当日退款当日净额归零，
 * 跨日退款（T+7 售后）在退款日记一笔负数，财务在账单上能单独看到冲回。
 */
@Service
public class DailyBillService {

    private static final Logger log = LoggerFactory.getLogger(DailyBillService.class);

    /** 账单日切固定 Asia/Shanghai，不跟随 JVM 默认时区 */
    private static final ZoneId BILL_ZONE = ZoneId.of("Asia/Shanghai");

    private final CouponDailyBillMapper billMapper;
    private final CouponSettlePartyDailyBillMapper settlePartyBillMapper;
    private final TransactionTemplate transactionTemplate;
    /** 主体账单开关：默认开（纯增量新表）；关闭后只出旧模板账单，便于回滚 */
    private final boolean settlePartyBillEnabled;

    public DailyBillService(CouponDailyBillMapper billMapper,
                            CouponSettlePartyDailyBillMapper settlePartyBillMapper,
                            PlatformTransactionManager transactionManager,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${coupon.billing.settle-party.enabled:true}")
                            boolean settlePartyBillEnabled) {
        this.billMapper = billMapper;
        this.settlePartyBillMapper = settlePartyBillMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.settlePartyBillEnabled = settlePartyBillEnabled;
    }

    /** 生成指定日期的账单（幂等，可重跑/补数）。返回聚合出的模板行数 */
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
     * 按结算主体生成指定日期的账单（V24，环 3）。与模板账单同源：
     * 正分账行计核销，退款明细/旧负分账行计冲回；唯一键 `(日期,appId,主体,模板)` 幂等覆盖。
     * 返回聚合出的主体行数。
     */
    public int generateSettlePartyBill(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        List<CouponSettlePartyDailyBillMapper.SettlePartyAgg> rows =
                settlePartyBillMapper.aggregateByDate(start, end);
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
            settlePartyBillMapper.upsertBill(bill);
        }
        log.info("SETTLE_PARTY_DAILY_BILL date={} parties/templates={} 核销行={} 退款行={}",
                date, rows.size(),
                rows.stream().mapToLong(CouponSettlePartyDailyBillMapper.SettlePartyAgg::getConfirmCount).sum(),
                rows.stream().mapToLong(CouponSettlePartyDailyBillMapper.SettlePartyAgg::getRefundCount).sum());
        return rows.size();
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
        reconcile(date);
    }

    /**
     * 对账：主体账单按模板汇总必须等于模板账单（金额/行数四项）。
     * 返回不一致的模板数，并逐条 ERROR 留现场；0 = 一致。
     *
     * <p>这是“多主体相加 = 模板账单”验收的运行时守卫，不只是一条测试断言。
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
            }
        }
        for (Long templateId : sums.keySet()) {
            if (!templates.containsKey(templateId)) {
                mismatches++;
                log.error("SETTLE_PARTY_BILL_RECONCILE_ORPHAN date={} templateId={} "
                        + "主体账单有行但模板账单没有", date, templateId);
            }
        }
        if (mismatches == 0) {
            log.info("SETTLE_PARTY_BILL_RECONCILE_OK date={} templates={}", date, templates.size());
        }
        return mismatches;
    }

    /**
     * 每天 00:30 出昨日账单（T+1，Asia/Shanghai）。幂等——错过/重跑由 generateAll 补。
     * 异常在事务边界外捕获：失败的那张账单已回滚，已提交的那张保留。
     */
    @Scheduled(cron = "0 30 0 * * ?", zone = "Asia/Shanghai")
    public void generateYesterdayBill() {
        try {
            generateAll(LocalDate.now(BILL_ZONE).minusDays(1));
        } catch (Exception e) {
            log.error("DAILY_BILL_FAIL 昨日账单生成失败（次日定时重跑或手动补数）", e);
        }
    }
}
