package com.newcoupon.domain.settle;

/**
 * V47 历史回填 SQL 的 Java 侧副本（{@code db/migration/V47__settle_quality_and_hold.sql} 第②段）。
 *
 * <p><b>为什么留副本</b>：Flyway 迁移在测试容器启动前执行，测试无法"先造迁移前数据再跑迁移"。
 * 回填的正确性改为解释性测试（{@code SettleQualityBillIntegrationTest}）：测试造出
 * "迁移前形状"的行，手工执行这里的同款 UPDATE，断言判定口径（JOIN funder_type、
 * {@code __default__} 哨兵、{@code funding_known}）正确分类。<b>改迁移里的回填必须同步改这里</b>
 * （两处注释互相指向；测试会抓住语义漂移，抓不住纯文本漂移——登记在 runbook）。
 */
public final class SettleBackfillSql {

    /** 商家/共担券的分账行落在 __default__ → LEGACY_DEFAULT + 不进真实结算（L76 核心） */
    public static final String SHARE_LEGACY_DEFAULT =
            "UPDATE coupon_share_detail s "
            + "JOIN coupon_template t ON t.id = s.template_id "
            + "SET s.quality_flags = s.quality_flags | 1, s.settle_eligible = 0 "
            + "WHERE s.settle_party_id = '__default__' AND t.funder_type IN (2, 3)";

    /** 同判定用在退款明细上（退款明细的主体从分账行复制，分账侧丢失它也丢失） */
    public static final String REFUND_LEGACY_DEFAULT =
            "UPDATE coupon_refund_detail d "
            + "JOIN coupon_template t ON t.id = d.template_id "
            + "SET d.quality_flags = d.quality_flags | 1, d.settle_eligible = 0 "
            + "WHERE d.settle_party_id = '__default__' AND t.funder_type IN (2, 3)";

    /** 老式订单级分账行（无行号）→ NO_ITEMS 只降观测粒度，保持可结算 */
    public static final String SHARE_NO_ITEMS =
            "UPDATE coupon_share_detail "
            + "SET quality_flags = quality_flags | 2 "
            + "WHERE order_line_no IS NULL AND quality_flags & 2 = 0";

    /** 出资不明的退款明细（关联退款单 funding_known=0）→ FUNDING_UNKNOWN + 不进真实结算 */
    public static final String REFUND_FUNDING_UNKNOWN =
            "UPDATE coupon_refund_detail d "
            + "JOIN coupon_refund_order o ON o.refund_no = d.refund_no "
            + "SET d.quality_flags = d.quality_flags | 4, d.settle_eligible = 0 "
            + "WHERE o.funding_known = 0";

    private SettleBackfillSql() {
    }
}
