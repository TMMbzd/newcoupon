package com.newcoupon.domain.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 券模板聚合不变量测试——"测试即需求文档"（docs/00 第 9 节）。
 * 每条校验规则对应一个用例：改规则必改测试，两边不一致 CI 立红。
 */
@DisplayName("券模板聚合不变量")
class CouponTemplateTest {

    private static CouponTemplate.Creation.CreationBuilder valid() {
        return CouponTemplate.Creation.builder()
                .templateCode("T20260904001")
                .activityId(1001L)
                .name("满100减10测试券")
                .couponType(CouponType.FULL_REDUCTION)
                .faceValue(1000L)        // 10 元，单位分
                .threshold(10000L)       // 门槛 100 元
                .totalCount(10000L)
                .perUserLimit(1)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.of(2026, 9, 4, 0, 0))
                .validEnd(LocalDateTime.of(2026, 9, 30, 23, 59, 59));
    }

    @Test
    @DisplayName("合法满减券创建成功，初始状态=已审批+未发布")
    void createValidTemplate() {
        CouponTemplate t = CouponTemplate.create(valid().build());
        assertThat(t.getId()).isNull();
        assertThat(t.getApproveStatus()).isEqualTo(ApproveStatus.APPROVED);
        assertThat(t.getPublishStatus()).isEqualTo(PublishStatus.UNPUBLISHED);
        assertThatCode(t::checkPublishable).doesNotThrowAnyException();
    }

    // ---------- 资损级不变量 ----------

    @Test
    @DisplayName("满折券缺优惠上限 → 拒绝（无上限会被大额订单一笔薅光预算）")
    void discountWithoutCapRejected() {
        assertThatThrownBy(() -> CouponTemplate.create(valid()
                .couponType(CouponType.DISCOUNT)
                .faceValue(8500L)          // 85 折
                .threshold(0L)
                .discountCap(null)         // ← 缺上限
                .build()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("优惠上限");
    }

    @Test
    @DisplayName("满折面额必须基点制 1~9900：传 8.5 折当 85 填（85 基点=99.15折的负数语义）会因范围校验暴露")
    void discountBasisPointRange() {
        assertThatThrownBy(() -> CouponTemplate.create(valid()
                .couponType(CouponType.DISCOUNT).faceValue(9950L).threshold(0L).discountCap(5000L).build()))
                .hasMessageContaining("基点");
        assertThatThrownBy(() -> CouponTemplate.create(valid()
                .couponType(CouponType.DISCOUNT).faceValue(0L).threshold(0L).discountCap(5000L).build()))
                .hasMessageContaining("面额");
        // 合法：8500 基点 + 上限
        assertThatCode(() -> CouponTemplate.create(valid()
                .couponType(CouponType.DISCOUNT).faceValue(8500L).threshold(0L).discountCap(5000L).build()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("满减券门槛必须>0；无门槛券门槛必须=0（两类语义不允许混填）")
    void thresholdRulesByType() {
        assertThatThrownBy(() -> CouponTemplate.create(valid().threshold(0L).build()))
                .hasMessageContaining("门槛");
        assertThatThrownBy(() -> CouponTemplate.create(valid()
                .couponType(CouponType.NO_THRESHOLD).threshold(5000L).build()))
                .hasMessageContaining("门槛");
    }

    // ---------- 有效期不变量 ----------

    @Test
    @DisplayName("固定区间：起止必填且开始<结束；相对有效期：天数 1~365")
    void validityRules() {
        assertThatThrownBy(() -> CouponTemplate.create(valid().validEnd(null).build()))
                .hasMessageContaining("起止时间");
        assertThatThrownBy(() -> CouponTemplate.create(valid()
                .validStart(LocalDateTime.of(2026, 10, 1, 0, 0))
                .validEnd(LocalDateTime.of(2026, 9, 1, 0, 0))
                .build()))
                .hasMessageContaining("早于");
        assertThatThrownBy(() -> CouponTemplate.create(valid()
                .validType(ValidType.AFTER_RECEIVE).validDays(400).validStart(null).validEnd(null).build()))
                .hasMessageContaining("1~365");
    }

    @Test
    @DisplayName("基础字段：负面额/零库存/空名称/限领<1 全部拒绝")
    void basicFieldGuards() {
        assertThatThrownBy(() -> CouponTemplate.create(valid().faceValue(0L).build()))
                .hasMessageContaining("面额");
        assertThatThrownBy(() -> CouponTemplate.create(valid().totalCount(0L).build()))
                .hasMessageContaining("库存");
        assertThatThrownBy(() -> CouponTemplate.create(valid().name("  ").build()))
                .hasMessageContaining("名称");
        assertThatThrownBy(() -> CouponTemplate.create(valid().perUserLimit(0).build()))
                .hasMessageContaining("限领");
    }

    @Test
    @DisplayName("withId 回填主键返回新实例（不可变风格，原实例不受影响）")
    void withIdReturnsNewInstance() {
        CouponTemplate t = CouponTemplate.create(valid().build());
        CouponTemplate withId = t.withId(42L);
        assertThat(t.getId()).isNull();
        assertThat(withId.getId()).isEqualTo(42L);
        assertThat(withId.getTemplateCode()).isEqualTo(t.getTemplateCode());
    }
}
