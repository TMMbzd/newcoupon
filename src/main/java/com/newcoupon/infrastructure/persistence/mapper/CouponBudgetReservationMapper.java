package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetReservationPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 预算预占 Mapper（V42）。所有状态迁移都是条件更新（status=0 才动）：
 * 双跑/重放时输家打 0 行自然幂等，不重复改账户。
 */
@Mapper
public interface CouponBudgetReservationMapper extends BaseMapper<CouponBudgetReservationPO> {

    /** 按预占单号定位（uk 覆盖）。 */
    @Select("SELECT * FROM coupon_budget_reservation WHERE reserve_no = #{reserveNo}")
    CouponBudgetReservationPO selectByReserveNo(@Param("reserveNo") String reserveNo);

    /**
     * 同一业务键的全部开口（未终态）预占，按 id 升序（先建的先消耗）。
     * retry-failed 会给同一 chunk 追加新预占——结算时按序吃掉开口额度。
     */
    @Select("SELECT * FROM coupon_budget_reservation WHERE biz_type = #{bizType} "
            + "AND biz_id = #{bizId} AND status = 0 ORDER BY id")
    List<CouponBudgetReservationPO> selectOpenByBiz(@Param("bizType") int bizType,
                                                    @Param("bizId") String bizId);

    /** 同一业务键的全部预占（含终态，按 id 升序）：结算结果断言与排障用。 */
    @Select("SELECT * FROM coupon_budget_reservation WHERE biz_type = #{bizType} "
            + "AND biz_id = #{bizId} ORDER BY id")
    List<CouponBudgetReservationPO> selectByBiz(@Param("bizType") int bizType,
                                                @Param("bizId") String bizId);

    /**
     * 累进确认：confirmed_amount 原子加 delta，条件是加完不超预占额。
     * status=0 条件保证终态后不再动——重复结算/过期回收竞态的输家在这里让路。
     */
    @Update("UPDATE coupon_budget_reservation SET confirmed_amount = confirmed_amount + #{delta}, "
            + "update_time = NOW(3) WHERE id = #{id} AND status = 0 "
            + "AND confirmed_amount + #{delta} <= amount")
    int confirmDelta(@Param("id") long id, @Param("delta") long delta);

    /**
     * 关闭到终态（2 释放 / 3 过期回收）：只有 status=0 的行打得中。
     * 与账户回池在同一事务（见 BudgetTxService），CHECK 保证不动已终态行。
     */
    @Update("UPDATE coupon_budget_reservation SET status = #{status}, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 0 AND confirmed_amount = #{seenConfirmed}")
    int close(@Param("id") long id, @Param("status") int status,
              @Param("seenConfirmed") long seenConfirmed);

    /**
     * 过期扫描：status=0 且 expire_time 早于 #{now}。两侧都是应用钟
     * （expire_time 由应用写入，now 由调用方传 LocalDateTime.now()）——同源不混用 DB NOW。
     */
    @Select("SELECT * FROM coupon_budget_reservation WHERE status = 0 "
            + "AND expire_time < #{now} ORDER BY id LIMIT #{limit}")
    List<CouponBudgetReservationPO> selectStale(@Param("now") LocalDateTime now,
                                                @Param("limit") int limit);

    /**
     * 对账等式②：某账户的开口预占合计（amount-confirmed 只属于未终态行），
     * 理论上恒等于账户 reserved。
     */
    @Select("SELECT COALESCE(SUM(amount - confirmed_amount), 0) "
            + "FROM coupon_budget_reservation WHERE account_id = #{accountId} AND status = 0")
    long sumOpenByAccount(@Param("accountId") long accountId);

    /** 对账：某业务键全部预占（含终态）的已确认合计——chunk 结算差额的依据。 */
    @Select("SELECT COALESCE(SUM(confirmed_amount), 0) FROM coupon_budget_reservation "
            + "WHERE biz_type = #{bizType} AND biz_id = #{bizId}")
    long sumConfirmedByBiz(@Param("bizType") int bizType, @Param("bizId") String bizId);
}
