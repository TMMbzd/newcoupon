package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponBudgetLedgerPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 预算账本流水 Mapper（V42）。只 INSERT 和 SELECT——流水是不可变事实，
 * 任何"改流水"的需求都意味着设计错了（该加新行，不该改旧行）。
 */
@Mapper
public interface CouponBudgetLedgerMapper extends BaseMapper<CouponBudgetLedgerPO> {

    /** 某账户的末条流水（id 最大）：其快照应等于账户行现值——对账等式①。 */
    @Select("SELECT * FROM coupon_budget_ledger WHERE account_id = #{accountId} "
            + "ORDER BY id DESC LIMIT 1")
    CouponBudgetLedgerPO selectLastByAccount(@Param("accountId") long accountId);

    /** 某账户的全部流水（测试断言与排障用），按 id 升序=时间序。 */
    @Select("SELECT * FROM coupon_budget_ledger WHERE account_id = #{accountId} ORDER BY id")
    List<CouponBudgetLedgerPO> selectByAccount(@Param("accountId") long accountId);

    /**
     * 某账户自某时刻起的流水行数（V6 4B 预算燃烧信号 R_BUDGET_BURN 的因子）：
     * 口径=**当日 RESERVE/CONFIRM/RELEASE/EXPIRE 全部行数**——衡量“账本被戳了多快”
     * 而不是“消耗了多少钱”（金额口径会被单张面额差异带偏：一张 100 元券的 RESERVE
     * 和一百张 1 元券的 RESERVE 金额相同，速度完全不同）。走 idx_account 前缀 +
     * create_time 过滤，一条 SQL。
     */
    @Select("SELECT COUNT(*) FROM coupon_budget_ledger "
            + "WHERE account_id = #{accountId} AND create_time >= #{since}")
    long countCreatedSince(@Param("accountId") long accountId,
                           @Param("since") java.time.LocalDateTime since);
}
