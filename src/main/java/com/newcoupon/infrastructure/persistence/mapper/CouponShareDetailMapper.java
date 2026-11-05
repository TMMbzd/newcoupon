package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponShareDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 成本分摊明细 Mapper（P5 核销事务写入；守恒校验 SUM(share_total)=discount_amt） */
@Mapper
public interface CouponShareDetailMapper extends BaseMapper<CouponShareDetailPO> {

    /** 按占用单号汇总分摊总额（守恒校验用） */
    @Select("SELECT COALESCE(SUM(share_total), 0) FROM coupon_share_detail WHERE usage_no = #{usageNo}")
    long sumShareByUsageNo(@Param("usageNo") String usageNo);

    /** 按占用单号取正分账行（核销快照；退款按行 × 数量读它算冲回额） */
    @Select("SELECT * FROM coupon_share_detail WHERE usage_no = #{usageNo} AND share_total > 0")
    List<CouponShareDetailPO> selectPositiveByUsageNo(@Param("usageNo") String usageNo);

    /**
     * 近一段期内该 (appId, 主体) 是否出现过分账行（V47 hold 的 matchedHistory 可见性）：
     * 主体拼错的冻结永远命不中账单——给 place 响应一个"这主体最近有账吗"的信号，
     * 走 idx_share_party，一条 EXISTS。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM coupon_share_detail "
            + "WHERE app_id = #{appId} AND settle_party_id = #{partyId} AND create_time >= #{since})")
    boolean existsPartyHistory(@Param("appId") String appId,
                               @Param("partyId") String partyId,
                               @Param("since") java.time.LocalDateTime since);
}
