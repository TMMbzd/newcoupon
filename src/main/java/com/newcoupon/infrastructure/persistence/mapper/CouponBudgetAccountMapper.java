package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetAccountPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 预算账户 Mapper（V42）。核心是 {@link #tryReserve} 的条件更新：
 * `reserved+consumed+amount<=total` 放在 WHERE 里，1000 并发抢最后一份预算
 * 也只有能打中条件的赢家成功——与 coupon_stock_shard 的库存条件更新同构。
 * 不存在"先 SELECT 余额再 UPDATE"的用法：读-改-写两步在并发下必然超卖。
 */
@Mapper
public interface CouponBudgetAccountMapper extends BaseMapper<CouponBudgetAccountPO> {

    /** 按唯一组合定位账户（uk_scope_period 覆盖）；null=账户不存在（懒建判据）。 */
    @Select("SELECT * FROM coupon_budget_account WHERE scope_type = #{scopeType} "
            + "AND scope_id = #{scopeId} AND period_type = #{periodType} "
            + "AND period_key = #{periodKey}")
    CouponBudgetAccountPO selectByScope(@Param("scopeType") int scopeType,
                                        @Param("scopeId") long scopeId,
                                        @Param("periodType") int periodType,
                                        @Param("periodKey") String periodKey);

    /**
     * 预占：reserved 原子加 amount，条件是加完仍不超池（reserved+consumed+amount<=total）。
     * 返回 1=预占成功；0=预算不足（或账户行不存在——调用方需区分，见 selectByScope）。
     * version 同步自增留给乐观锁审计；池约束本身由 WHERE 保证 + CHECK 兜底。
     */
    @Update("UPDATE coupon_budget_account SET reserved = reserved + #{amount}, "
            + "version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND reserved + consumed + #{amount} <= total")
    int tryReserve(@Param("id") long id, @Param("amount") long amount);

    /**
     * 确认转移：预留额 rel 回池（reserved 减）、实际消耗 cons 入账（consumed 加）。
     * rel >= cons 由调用方保证（实际消耗不超过预占），reserved/consumed 非负与
     * 池约束由表 CHECK 兜底——正常路径下本更新只会让 reserved+consumed 不增。
     */
    @Update("UPDATE coupon_budget_account SET reserved = reserved - #{releaseAmount}, "
            + "consumed = consumed + #{consumeAmount}, version = version + 1, "
            + "update_time = NOW(3) WHERE id = #{id}")
    int confirmTransfer(@Param("id") long id,
                        @Param("releaseAmount") long releaseAmount,
                        @Param("consumeAmount") long consumeAmount);

    /**
     * 回池：reserved 减去回补额（释放/过期回收共用）。releaseAmount 由调用方按
     * 预占行的开口余额（amount-confirmed）算好传入。
     */
    @Update("UPDATE coupon_budget_account SET reserved = reserved - #{releaseAmount}, "
            + "version = version + 1, update_time = NOW(3) WHERE id = #{id}")
    int release(@Param("id") long id, @Param("releaseAmount") long releaseAmount);

    /**
     * 事务内读账户现值（已被同事务的 UPDATE 持行锁，读到的是本次变动后的值）：
     * 供 ledger 写 balance_after 快照——流水与余额在同一把锁下成对落库。
     */
    @Select("SELECT * FROM coupon_budget_account WHERE id = #{id}")
    CouponBudgetAccountPO selectByIdForSnapshot(@Param("id") long id);

    /** 对账用：全部账户（MVP 活动级账户数量=运营建的活动数，全表扫可控）。 */
    @Select("SELECT * FROM coupon_budget_account")
    java.util.List<CouponBudgetAccountPO> selectAll();
}
