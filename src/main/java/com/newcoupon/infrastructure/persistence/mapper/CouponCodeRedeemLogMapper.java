package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponCodeRedeemLogPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 兑换流水 Mapper（V41）。流水只插入不更新：一行=一次尝试的终局；
 * 失败原因是防枚举口径下唯一能区分"码不存在/已用/限流"的地方（不进响应）。
 */
@Mapper
public interface CouponCodeRedeemLogMapper extends BaseMapper<CouponCodeRedeemLogPO> {

    /** 按批次取流水（idx_batch_create 覆盖），时间升序；对账/测试断言用。 */
    @Select("SELECT * FROM coupon_code_redeem_log WHERE batch_id = #{batchId} ORDER BY id")
    List<CouponCodeRedeemLogPO> selectByBatch(@Param("batchId") long batchId);
}
