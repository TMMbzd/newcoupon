package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponSettlePartyDailyBillPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 按结算主体的 T+1 日账单 Mapper（V24，环 3）。
 *
 * <p>聚合口径与模板日账单同源：正分账行计核销；`coupon_refund_detail` 计冲回；
 * 旧版写在 `coupon_share_detail` 的负行也计冲回。**旧负行取 `-ABS(...)`**——
 * 负行本身已是负数，直接取原值即可净额；早期 SQL 的 `-share_total` 会把它翻成正数，
 * 这里一并修正（模板账单同步修）。
 */
@Mapper
public interface CouponSettlePartyDailyBillMapper extends BaseMapper<CouponSettlePartyDailyBillPO> {

    /** 聚合行（MyBatis 按列别名映射） */
    class SettlePartyAgg {
        private String appId;
        private String settlePartyId;
        private Long templateId;
        private Long confirmCount;
        private Long refundCount;
        private Long discountTotal;
        private Long platformBurden;
        private Long merchantBurden;

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getSettlePartyId() { return settlePartyId; }
        public void setSettlePartyId(String settlePartyId) { this.settlePartyId = settlePartyId; }
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
     * 沙箱过滤（V6 2B）：与模板账单同条件（LEFT JOIN open_app 只聚合 PROD）——
     * 两张账单的对账等式要求过滤口径完全一致，只滤一张必然 reconcile 报差异。
     */
    @Select("SELECT t.app_id COLLATE utf8mb4_bin AS appId, " +
            "       settle_party_id COLLATE utf8mb4_bin AS settlePartyId, template_id AS templateId, " +
            "       COALESCE(SUM(confirmCount), 0) AS confirmCount, " +
            "       COALESCE(SUM(refundCount), 0) AS refundCount, " +
            "       COALESCE(SUM(discountTotal), 0) AS discountTotal, " +
            "       COALESCE(SUM(platformBurden), 0) AS platformBurden, " +
            "       COALESCE(SUM(merchantBurden), 0) AS merchantBurden " +
            "FROM ( " +
            "  SELECT app_id, settle_party_id, template_id, 1 AS confirmCount, 0 AS refundCount, " +
            "         share_total AS discountTotal, platform_burden AS platformBurden, " +
            "         merchant_burden AS merchantBurden, create_time " +
            "  FROM coupon_share_detail WHERE share_total > 0 " +
            "  UNION ALL " +
            "  SELECT app_id, settle_party_id, template_id, 0, 1, " +
            "         -ABS(share_total), -ABS(platform_burden), -ABS(merchant_burden), create_time " +
            "  FROM coupon_share_detail WHERE share_total < 0 " +
            "  UNION ALL " +
            "  SELECT app_id, settle_party_id, template_id, 0, 1, " +
            "         -refund_discount_amt, -refund_platform_amt, -refund_merchant_amt, create_time " +
            "  FROM coupon_refund_detail " +
            ") t LEFT JOIN open_app a ON a.app_id = t.app_id " +
            "WHERE t.create_time >= #{start} AND t.create_time < #{end} " +
            "AND COALESCE(a.environment, 'PROD') = 'PROD' " +
            "GROUP BY t.app_id COLLATE utf8mb4_bin, " +
            "         settle_party_id COLLATE utf8mb4_bin, template_id")
    List<SettlePartyAgg> aggregateByDate(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /** 幂等落账：唯一键冲突整体覆盖最新聚合值 */
    @Insert("INSERT INTO coupon_settle_party_daily_bill " +
            "(bill_date, app_id, settle_party_id, template_id, confirm_count, refund_count, " +
            " discount_total, platform_burden, merchant_burden) " +
            "VALUES (#{billDate}, #{appId}, #{settlePartyId}, #{templateId}, #{confirmCount}, " +
            "        #{refundCount}, #{discountTotal}, #{platformBurden}, #{merchantBurden}) " +
            "ON DUPLICATE KEY UPDATE " +
            "  confirm_count = VALUES(confirm_count), refund_count = VALUES(refund_count), " +
            "  discount_total = VALUES(discount_total), platform_burden = VALUES(platform_burden), " +
            "  merchant_burden = VALUES(merchant_burden), update_time = CURRENT_TIMESTAMP")
    int upsertBill(CouponSettlePartyDailyBillPO bill);

    @Select("<script>SELECT * FROM coupon_settle_party_daily_bill " +
            "WHERE bill_date = #{billDate} " +
            "<if test='appId != null'>AND app_id = #{appId} </if>" +
            "<if test='settlePartyId != null'>AND settle_party_id = #{settlePartyId} </if>" +
            "<if test='templateId != null'>AND template_id = #{templateId} </if>" +
            "ORDER BY app_id, settle_party_id, template_id LIMIT #{size} OFFSET #{offset}</script>")
    List<CouponSettlePartyDailyBillPO> selectByFilters(@Param("billDate") LocalDate billDate,
                                                       @Param("appId") String appId,
                                                       @Param("settlePartyId") String settlePartyId,
                                                       @Param("templateId") Long templateId,
                                                       @Param("size") int size,
                                                       @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) FROM coupon_settle_party_daily_bill " +
            "WHERE bill_date = #{billDate} " +
            "<if test='appId != null'>AND app_id = #{appId} </if>" +
            "<if test='settlePartyId != null'>AND settle_party_id = #{settlePartyId} </if>" +
            "<if test='templateId != null'>AND template_id = #{templateId} </if>" +
            "</script>")
    long countByFilters(@Param("billDate") LocalDate billDate,
                        @Param("appId") String appId,
                        @Param("settlePartyId") String settlePartyId,
                        @Param("templateId") Long templateId);

    /** 对账用：取某日全部主体账单行（不加分页；单日规模可控） */
    @Select("SELECT * FROM coupon_settle_party_daily_bill WHERE bill_date = #{billDate}")
    List<CouponSettlePartyDailyBillPO> selectAllByDate(@Param("billDate") LocalDate billDate);
}
