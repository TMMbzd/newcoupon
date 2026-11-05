package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponSettlementHoldPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券侧应付冻结 Mapper（V47）。
 *
 * <p>状态迁移全部用**条件 UPDATE**（WHERE 带 status='ACTIVE'）：并发 release/void 只有一个
 * 能改中，输家拿到 0 行后按当前状态分类（幂等成功或非法迁移拒绝）——不加 SELECT FOR UPDATE，
 * 与退款/预算账户的条件更新同一并发纪律。
 */
@Mapper
public interface CouponSettlementHoldMapper extends BaseMapper<CouponSettlementHoldPO> {

    @Select("SELECT * FROM coupon_settlement_hold WHERE hold_no = #{holdNo}")
    CouponSettlementHoldPO selectByHoldNo(@Param("holdNo") String holdNo);

    /**
     * ACTIVE → 终态（RELEASED/VOIDED 由调用方传目标状态）。
     * 返回 1=本次迁移成功；0=非 ACTIVE（已终态/不存在），调用方读当前行分类。
     */
    @Update("UPDATE coupon_settlement_hold "
            + "SET status = #{target}, released_by = #{operator}, released_at = #{now} "
            + "WHERE hold_no = #{holdNo} AND status = 'ACTIVE'")
    int transitionFromActive(@Param("holdNo") String holdNo,
                             @Param("target") String target,
                             @Param("operator") String operator,
                             @Param("now") LocalDateTime now);

    /** ACTIVE hold 金额汇总（账单生成用）：按 (app, 主体, 模板) 分组，template_id NULL=主体级 */
    @Select("SELECT app_id AS appId, settle_party_id AS settlePartyId, template_id AS templateId, "
            + "       SUM(amount) AS totalAmount "
            + "FROM coupon_settlement_hold WHERE status = 'ACTIVE' "
            + "GROUP BY app_id, settle_party_id, template_id")
    List<HoldAgg> sumActiveGrouped();

    /**
     * ACTIVE hold 总额（4B 指标 coupon_risk_hold_amount 的数据源）：RISK_RULE 与 MANUAL
     * 合计——盘面只关心“多少应付被扣住”，来源拆分去查 hold 表/审计。空表返回 0。
     */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM coupon_settlement_hold WHERE status = 'ACTIVE'")
    Long sumActiveAmount();

    /** 汇总行（MyBatis 按列别名映射；templateId 为 NULL 表示主体级 hold） */
    class HoldAgg {
        private String appId;
        private String settlePartyId;
        private Long templateId;
        private Long totalAmount;

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getSettlePartyId() { return settlePartyId; }
        public void setSettlePartyId(String settlePartyId) { this.settlePartyId = settlePartyId; }
        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long templateId) { this.templateId = templateId; }
        public Long getTotalAmount() { return totalAmount; }
        public void setTotalAmount(Long totalAmount) { this.totalAmount = totalAmount; }
    }
}
