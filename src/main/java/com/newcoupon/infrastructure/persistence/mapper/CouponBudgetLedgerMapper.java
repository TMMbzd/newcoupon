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
     * 和一百张 1 元券的 RESERVE 金额相同，速度完全不同）。
     *
     * <p><b>FORCE INDEX（审查 M2，V49）</b>：必须走 (account_id, create_time) 复合索引的
     * range scan（扫描量=当日行数，有界）。不加 hint 时优化器在行数少的账户上会与
     * idx_account 单列索引打平手并可能选它——那是**沿账户全部历史行逐行过滤时间**的
     * O(全史) 退化（只追加表只增不减，且攻击者每领一次既触发扫描又添两行=二次方自增强）。
     * hint 的失败模式也更诚实：索引被误删时退化为全表扫（可见地慢），而不是静默回到
     * 扫全史。
     */
    @Select("SELECT COUNT(*) FROM coupon_budget_ledger FORCE INDEX (idx_account_time) "
            + "WHERE account_id = #{accountId} AND create_time >= #{since}")
    long countCreatedSince(@Param("accountId") long accountId,
                           @Param("since") java.time.LocalDateTime since);
}
