package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponRefundOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 退款单 Mapper（V21）：refund_no 唯一 = 幂等锚点。 */
@Mapper
public interface CouponRefundOrderMapper extends BaseMapper<CouponRefundOrderPO> {

    @Select("SELECT * FROM coupon_refund_order WHERE refund_no = #{refundNo}")
    CouponRefundOrderPO selectByRefundNo(@Param("refundNo") String refundNo);

    /**
     * 并发同号退款的锁读：InnoDB 当前读能看到先到事务已提交的行，
     * 避免 REPEATABLE READ 快照看不到对手、随后撞唯一键变成 500。
     */
    @Select("SELECT * FROM coupon_refund_order WHERE refund_no = #{refundNo} FOR UPDATE")
    CouponRefundOrderPO selectByRefundNoForUpdate(@Param("refundNo") String refundNo);

    /** 同一占用单已发生的退款次数（部分退款次数上限） */
    @Select("SELECT COUNT(*) FROM coupon_refund_order WHERE usage_no = #{usageNo}")
    int countByUsageNo(@Param("usageNo") String usageNo);

    // ---------- 环 3：内部面退款查询（POST + X-Admin-Token） ----------

    @Select("<script>SELECT o.* FROM coupon_refund_order o " +
            "<where>" +
            "<if test='usageNo != null'>AND o.usage_no = #{usageNo} </if>" +
            "<if test='refundNo != null'>AND o.refund_no = #{refundNo} </if>" +
            "<if test='orderNo != null'>AND o.order_no = #{orderNo} </if>" +
            "<if test='appId != null'>AND BINARY o.app_id = BINARY #{appId} </if>" +
            "<if test='settlePartyId != null'>AND EXISTS (" +
            "  SELECT 1 FROM coupon_refund_detail d " +
            "  WHERE d.refund_no = o.refund_no AND BINARY d.app_id = BINARY #{appId} " +
            "    AND BINARY d.settle_party_id = BINARY #{settlePartyId}) </if>" +
            "<if test='fromTime != null'>AND o.create_time &gt;= #{fromTime} </if>" +
            "<if test='toTime != null'>AND o.create_time &lt; #{toTime} </if>" +
            "</where>" +
            " ORDER BY o.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    java.util.List<CouponRefundOrderPO> selectByFilters(
            @Param("usageNo") String usageNo,
            @Param("refundNo") String refundNo,
            @Param("orderNo") String orderNo,
            @Param("appId") String appId,
            @Param("settlePartyId") String settlePartyId,
            @Param("fromTime") java.time.LocalDateTime fromTime,
            @Param("toTime") java.time.LocalDateTime toTime,
            @Param("size") int size,
            @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) FROM coupon_refund_order o " +
            "<where>" +
            "<if test='usageNo != null'>AND o.usage_no = #{usageNo} </if>" +
            "<if test='refundNo != null'>AND o.refund_no = #{refundNo} </if>" +
            "<if test='orderNo != null'>AND o.order_no = #{orderNo} </if>" +
            "<if test='appId != null'>AND BINARY o.app_id = BINARY #{appId} </if>" +
            "<if test='settlePartyId != null'>AND EXISTS (" +
            "  SELECT 1 FROM coupon_refund_detail d " +
            "  WHERE d.refund_no = o.refund_no AND BINARY d.app_id = BINARY #{appId} " +
            "    AND BINARY d.settle_party_id = BINARY #{settlePartyId}) </if>" +
            "<if test='fromTime != null'>AND o.create_time &gt;= #{fromTime} </if>" +
            "<if test='toTime != null'>AND o.create_time &lt; #{toTime} </if>" +
            "</where></script>")
    long countByFilters(@Param("usageNo") String usageNo,
                        @Param("refundNo") String refundNo,
                        @Param("orderNo") String orderNo,
                        @Param("appId") String appId,
                        @Param("settlePartyId") String settlePartyId,
                        @Param("fromTime") java.time.LocalDateTime fromTime,
                        @Param("toTime") java.time.LocalDateTime toTime);
}
