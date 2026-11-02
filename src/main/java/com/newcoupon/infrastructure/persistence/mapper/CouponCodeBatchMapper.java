package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponCodeBatchPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 兑换码批次 Mapper（V41）。used 计数与 export_state 都走条件更新（CAS）：
 * used 只在码行 markUsed 成功的事务里 +1（used ≤ total 由 CHECK 约束终审），
 * export_state 0→1 只允许一次——明文 CSV 的"一次性"由此保证。
 */
@Mapper
public interface CouponCodeBatchMapper extends BaseMapper<CouponCodeBatchPO> {

    /** 按批次号精确定位（对外暴露 batchNo 而非自增 id）。 */
    @Select("SELECT * FROM coupon_code_batch WHERE batch_no = #{batchNo}")
    CouponCodeBatchPO selectByBatchNo(@Param("batchNo") String batchNo);

    /** 受理幂等：按租户 + requestId 定位既有批次（uk 终审）。 */
    @Select("SELECT * FROM coupon_code_batch WHERE app_id = #{appId} AND request_id = #{requestId}")
    CouponCodeBatchPO selectByAppAndRequest(@Param("appId") String appId,
                                            @Param("requestId") String requestId);

    /**
     * 一次性导出闸门：export_state 0→1。返回 1=本请求是首次导出（赢家），
     * 0=已导出过（输家 → 4002）。并发双请求时 CAS 保证只有一个赢家。
     */
    @Update("UPDATE coupon_code_batch SET export_state = 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND export_state = 0")
    int markExported(@Param("id") long id);

    /** 兑换成功记账：used+1（与码行 markUsed 同事务，只在码行 CAS 成功时调用）。 */
    @Update("UPDATE coupon_code_batch SET used = used + 1, update_time = NOW(3) WHERE id = #{id}")
    int addUsed(@Param("id") long id);
}
