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
     *
     * <p><b>旧负行取 `-ABS(...)`</b>：负行本身的金额已经是负数，直接相加即可净额；
     * 早期写法 `-share_total` 会把负数翻成正数、反而虚增账单。V24 起修正。
     *
     * <p><b>可结算口径（V47）</b>：三个 UNION 分支都只取 {@code settle_eligible=1}——
     * 质量排除行（商家钱落 __default__/出资不明）不进任何一张真实账单。
     * 主体账单（CouponSettlePartyDailyBillMapper）必须同步同过滤，否则 reconcile 对不上。
     * 被排除行数在主体账单侧进 ineligible_*_count（模板账单不加列——旧表不动）。
     *
     * <p><b>沙箱过滤（V6 2B）</b>：JOIN open_app 只聚合 PROD 租户的行——沙箱是联调数据，
     * 进账单就是"假钱进真账"。detail 行自带 app_id（V22 起 NOT NULL）；
     * LEFT JOIN + COALESCE 按 PROD 处理"归属行缺失"的边角（open_app 行被物理删之类，
     * 理论不可达——DELETED 是逻辑死），钱的口径宁可多算进人工复核也不静默漏账。
     * 主体账单（同日同源）必须用同一个过滤条件，否则 reconcile 对不上。
     */
    @Select("SELECT template_id AS templateId, " +
            "       COALESCE(SUM(confirmCount), 0) AS confirmCount, " +
            "       COALESCE(SUM(refundCount), 0) AS refundCount, " +
            "       COALESCE(SUM(discountTotal), 0) AS discountTotal, " +
            "       COALESCE(SUM(platformBurden), 0) AS platformBurden, " +
            "       COALESCE(SUM(merchantBurden), 0) AS merchantBurden " +
            "FROM ( " +
            "  SELECT app_id, template_id, 1 AS confirmCount, 0 AS refundCount, " +
            "         share_total AS discountTotal, platform_burden AS platformBurden, " +
            "         merchant_burden AS merchantBurden, create_time " +
            "  FROM coupon_share_detail WHERE share_total > 0 AND settle_eligible = 1 " +
            "  UNION ALL " +
            "  SELECT app_id, template_id, 0, 1, " +
            "         -ABS(share_total), -ABS(platform_burden), -ABS(merchant_burden), create_time " +
            "  FROM coupon_share_detail WHERE share_total < 0 AND settle_eligible = 1 " +
            "  UNION ALL " +
            "  SELECT app_id, template_id, 0, 1, -refund_discount_amt, -refund_platform_amt, " +
            "         -refund_merchant_amt, create_time " +
            "  FROM coupon_refund_detail WHERE settle_eligible = 1 " +
            ") t LEFT JOIN open_app a ON a.app_id = t.app_id " +
            "WHERE t.create_time >= #{start} AND t.create_time < #{end} " +
            "AND COALESCE(a.environment, 'PROD') = 'PROD' " +
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

    // ---------- 环 3：内部面模板账单查询 ----------

    /** 模板账单查询行（join 模板补 name/app_id；模板账单本身没有 app_id） */
    class TemplateBillRow {
        private LocalDate billDate;
        private Long templateId;
        private String templateName;
        private String appId;
        private Integer confirmCount;
        private Integer refundCount;
        private Long discountTotal;
        private Long platformBurden;
        private Long merchantBurden;

        public LocalDate getBillDate() { return billDate; }
        public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long templateId) { this.templateId = templateId; }
        public String getTemplateName() { return templateName; }
        public void setTemplateName(String templateName) { this.templateName = templateName; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public Integer getConfirmCount() { return confirmCount; }
        public void setConfirmCount(Integer confirmCount) { this.confirmCount = confirmCount; }
        public Integer getRefundCount() { return refundCount; }
        public void setRefundCount(Integer refundCount) { this.refundCount = refundCount; }
        public Long getDiscountTotal() { return discountTotal; }
        public void setDiscountTotal(Long discountTotal) { this.discountTotal = discountTotal; }
        public Long getPlatformBurden() { return platformBurden; }
        public void setPlatformBurden(Long platformBurden) { this.platformBurden = platformBurden; }
        public Long getMerchantBurden() { return merchantBurden; }
        public void setMerchantBurden(Long merchantBurden) { this.merchantBurden = merchantBurden; }
    }

    @Select("<script>SELECT b.bill_date AS billDate, b.template_id AS templateId, " +
            "       t.name AS templateName, t.app_id AS appId, " +
            "       b.confirm_count AS confirmCount, b.refund_count AS refundCount, " +
            "       b.discount_total AS discountTotal, b.platform_burden AS platformBurden, " +
            "       b.merchant_burden AS merchantBurden " +
            "FROM coupon_daily_bill b LEFT JOIN coupon_template t ON t.id = b.template_id " +
            "WHERE b.bill_date = #{billDate} " +
            "<if test='appId != null'>AND BINARY t.app_id = BINARY #{appId} </if>" +
            "<if test='templateId != null'>AND b.template_id = #{templateId} </if>" +
            "ORDER BY b.template_id LIMIT #{size} OFFSET #{offset}</script>")
    List<TemplateBillRow> selectByFilters(@Param("billDate") LocalDate billDate,
                                          @Param("appId") String appId,
                                          @Param("templateId") Long templateId,
                                          @Param("size") int size,
                                          @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) FROM coupon_daily_bill b " +
            "LEFT JOIN coupon_template t ON t.id = b.template_id " +
            "WHERE b.bill_date = #{billDate} " +
            "<if test='appId != null'>AND BINARY t.app_id = BINARY #{appId} </if>" +
            "<if test='templateId != null'>AND b.template_id = #{templateId} </if>" +
            "</script>")
    long countByFilters(@Param("billDate") LocalDate billDate,
                        @Param("appId") String appId,
                        @Param("templateId") Long templateId);
}
