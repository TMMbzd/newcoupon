package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponPricingSnapshotPO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 价格快照 Mapper（V23）：pricing_id 唯一；live_digest 只在 ACTIVE 时非空。 */
@Mapper
public interface CouponPricingSnapshotMapper extends BaseMapper<CouponPricingSnapshotPO> {

    @Select("SELECT * FROM coupon_pricing_snapshot WHERE pricing_id = #{pricingId}")
    CouponPricingSnapshotPO selectByPricingId(@Param("pricingId") String pricingId);

    @Select("SELECT * FROM coupon_pricing_snapshot WHERE app_id = #{appId} "
            + "AND user_id = #{userId} AND order_no = #{orderNo} "
            + "AND live_digest = #{liveDigest}")
    CouponPricingSnapshotPO selectLiveByDigest(@Param("appId") String appId,
                                               @Param("userId") long userId,
                                               @Param("orderNo") String orderNo,
                                               @Param("liveDigest") String liveDigest);

    /**
     * 锁券绑定：未过期才续期并记绑定时间。返回 0 = 快照已过期/已被撤销，
     * 调用方必须让锁券事务整体回滚（4007）。
     */
    @Update("UPDATE coupon_pricing_snapshot "
            + "SET expires_at = GREATEST(expires_at, #{minExpiresAt}), last_bound_at = #{now} "
            + "WHERE pricing_id = #{pricingId} AND live_digest IS NOT NULL AND expires_at > #{now}")
    int renewOnLock(@Param("pricingId") String pricingId,
                    @Param("minExpiresAt") LocalDateTime minExpiresAt,
                    @Param("now") LocalDateTime now);

    /** 损坏/撤销：清空 live_digest（不物理删除，保留审计） */
    @Update("UPDATE coupon_pricing_snapshot SET live_digest = NULL WHERE pricing_id = #{pricingId}")
    int clearLiveDigest(@Param("pricingId") String pricingId);

    /** 已过期才清空（并发重建前的机会式过期） */
    @Update("UPDATE coupon_pricing_snapshot SET live_digest = NULL "
            + "WHERE pricing_id = #{pricingId} AND expires_at <= #{now}")
    int clearLiveDigestIfExpired(@Param("pricingId") String pricingId,
                                 @Param("now") LocalDateTime now);

    /** 同摘要重建前：把已过期仍占着 live_digest 的行置空 */
    @Update("UPDATE coupon_pricing_snapshot SET live_digest = NULL "
            + "WHERE app_id = #{appId} AND user_id = #{userId} AND order_no = #{orderNo} "
            + "AND live_digest = #{liveDigest} AND expires_at <= #{now}")
    int clearExpiredLiveByDigest(@Param("appId") String appId,
                                 @Param("userId") long userId,
                                 @Param("orderNo") String orderNo,
                                 @Param("liveDigest") String liveDigest,
                                 @Param("now") LocalDateTime now);

    /** 清理第一步：过期行置空 live_digest（保留历史行供审计） */
    @Update("UPDATE coupon_pricing_snapshot SET live_digest = NULL "
            + "WHERE live_digest IS NOT NULL AND expires_at <= #{now} LIMIT #{limit}")
    int expireLive(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 清理第二步：删除保留期外的历史行 */
    @Delete("DELETE FROM coupon_pricing_snapshot "
            + "WHERE live_digest IS NULL AND expires_at <= #{now} LIMIT #{limit}")
    int deleteExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
