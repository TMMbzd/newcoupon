package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponDailyBillPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * T+1 日账单 Mapper：聚合 coupon_share_detail 正行 - coupon_refund_detail 退款明细
 * （兼容旧版负分账行）→ 幂等落 coupon_daily_bill。两张表都未分片，聚合不需要 scatter-gather。
 */
@Mapper
public interface CouponDailyBillMapper extends BaseMapper<CouponDailyBillPO> {

    /** 日聚合行（MyBatis 按列别名映射到内部类字段） */
    class DailyAgg {
        private Long templateId;
        private Long confirmCount;
        private Long refundCount;
        private Long discountTotal;
        private Long platformBurden;
        private Long merchantBurden;

        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long templateId) { this.templateId = templateId; }
        public Long getConfirmCount() { return confirmCount; }
        public void setConfirmCount(Long confirmCount) { this.confirmCount = confirmCount; }
        public Long getRefundCount() { return refundCount; }
        public void setRefundCount(Long refundCount) { this.refundCount = refundCount; }
        public Long getDiscountTotal() { return discountTotal; }
        public void setDiscountTotal(Long discountTotal) { this.discountTotal = discountTotal; }
        public Long getPlatformBurden() { return platformBurden; }
        public void setPlatformBurden(Long platformBurden) { this.platformBurden = platformBurden; }
        public Long getMerchantBurden() { return merchantBurden; }
        public void setMerchantBurden(Long merchantBurden) { this.merchantBurden = merchantBurden; }
    }

    /**
     * 按 [start, end) 聚合当日账目：
     * 正分账行计核销；退款明细（coupon_refund_detail）计冲回；旧版负分账行也兼容计入冲回。
     * 金额净额 = 正行 - 退款明细 - 旧负行。
     */
    @Select("SELECT template_id AS templateId, " +
            "       COALESCE(SUM(confirmCount), 0) AS confirmCount, " +
            "       COALESCE(SUM(refundCount), 0) AS refundCount, " +
            "       COALESCE(SUM(discountTotal), 0) AS discountTotal, " +
            "       COALESCE(SUM(platformBurden), 0) AS platformBurden, " +
            "       COALESCE(SUM(merchantBurden), 0) AS merchantBurden " +
            "FROM ( " +
            "  SELECT template_id, 1 AS confirmCount, 0 AS refundCount, " +
            "         share_total AS discountTotal, platform_burden AS platformBurden, " +
            "         merchant_burden AS merchantBurden, create_time " +
            "  FROM coupon_share_detail WHERE share_total > 0 " +
            "  UNION ALL " +
            "  SELECT template_id, 0, 1, -share_total, -platform_burden, -merchant_burden, create_time " +
            "  FROM coupon_share_detail WHERE share_total < 0 " +
            "  UNION ALL " +
            "  SELECT template_id, 0, 1, -refund_discount_amt, -refund_platform_amt, " +
            "         -refund_merchant_amt, create_time " +
            "  FROM coupon_refund_detail " +
            ") t WHERE create_time >= #{start} AND create_time < #{end} " +
            "GROUP BY template_id")
    List<DailyAgg> aggregateByDate(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 幂等落账单：uk(bill_date, template_id) 冲突时整体覆盖（重跑=取最新聚合值） */
    @Insert("INSERT INTO coupon_daily_bill " +
            "(bill_date, template_id, confirm_count, refund_count, discount_total, platform_burden, merchant_burden) " +
            "VALUES (#{billDate}, #{templateId}, #{confirmCount}, #{refundCount}, #{discountTotal}, #{platformBurden}, #{merchantBurden}) " +
            "ON DUPLICATE KEY UPDATE " +
            "  confirm_count = VALUES(confirm_count), refund_count = VALUES(refund_count), " +
            "  discount_total = VALUES(discount_total), platform_burden = VALUES(platform_burden), " +
            "  merchant_burden = VALUES(merchant_burden), update_time = CURRENT_TIMESTAMP")
    int upsertBill(CouponDailyBillPO bill);

    /** 读账单（测试/查询用） */
    @Select("SELECT * FROM coupon_daily_bill WHERE bill_date = #{billDate} ORDER BY template_id")
    List<CouponDailyBillPO> selectByDate(@Param("billDate") LocalDate billDate);
}
