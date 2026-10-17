package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper;
import com.newcoupon.infrastructure.persistence.po.CouponDailyBillPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    private final CouponDailyBillMapper billMapper;

    public DailyBillService(CouponDailyBillMapper billMapper) {
        this.billMapper = billMapper;
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

    /** 每天 00:30 出昨日账单（T+1）。幂等——错过/重跑由 generateBill 补 */
    @Scheduled(cron = "0 30 0 * * ?")
    public void generateYesterdayBill() {
        try {
            generateBill(LocalDate.now().minusDays(1));
        } catch (Exception e) {
            log.error("DAILY_BILL_FAIL 昨日账单生成失败（次日定时重跑或手动补数）", e);
        }
    }
}
