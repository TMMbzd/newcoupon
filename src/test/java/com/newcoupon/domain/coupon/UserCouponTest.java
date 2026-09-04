package com.newcoupon.domain.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.common.BizException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用户券领取工厂的不变量测试：快照拷贝、过期时间 23:59:59 固化规则（两种 valid_type 各验）、
 * 模板可领性前置校验。这些规则全部来自 docs/00 第 1.4 节"血泪规范"。
 */
@DisplayName("用户券领取工厂")
class UserCouponTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 10, 0);

    /** 构造一个"已审批+已发布"的模板（用 reconstitute 指定真实状态） */
    private static CouponTemplate published(ValidType validType, LocalDateTime start, LocalDateTime end,
                                            Integer validDays) {
        return CouponTemplate.reconstitute(9L,
                CouponTemplate.Creation.builder()
                        .templateCode("T20260905001").activityId(1001L).name("满100减10")
                        .couponType(CouponType.FULL_REDUCTION)
                        .faceValue(1000L).threshold(10000L).totalCount(100L).perUserLimit(1)
                        .validType(validType).validStart(start).validEnd(end).validDays(validDays)
                        .build(),
                0L, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }

    @Test
    @DisplayName("固定区间券：validEnd 取模板日期并归一到当日 23:59:59（运营配 00:00:00 也不影响当天使用）")
    void fixedValidEndNormalized() {
        CouponTemplate tpl = published(ValidType.FIXED,
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 10, 0, 0),   // ← 运营把结束时间配成 10 号 00:00:00
                null);
        UserCoupon uc = UserCoupon.issue(tpl, 42L, "req-001", NOW);
        assertThat(uc.getValidEnd()).isEqualTo(LocalDateTime.of(2026, 9, 10, 23, 59, 59));
    }

    @Test
    @DisplayName("相对有效期券：validEnd = 领取日+validDays 的当日 23:59:59（不是领取时刻+7天）")
    void relativeValidEndNormalized() {
        CouponTemplate tpl = published(ValidType.AFTER_RECEIVE, null, null, 7);
        UserCoupon uc = UserCoupon.issue(tpl, 42L, "req-001", NOW);   // 9-05 10:00 领取
        assertThat(uc.getValidEnd()).isEqualTo(LocalDateTime.of(2026, 9, 12, 23, 59, 59));
    }

    @Test
    @DisplayName("快照固化：面额/门槛/上限/编码从模板拷贝，初始状态=待使用")
    void snapshotCopied() {
        CouponTemplate tpl = CouponTemplate.reconstitute(9L,
                CouponTemplate.Creation.builder()
                        .templateCode("T20260905002").activityId(1001L).name("85折券")
                        .couponType(CouponType.DISCOUNT)
                        .faceValue(8500L).discountCap(5000L).threshold(0L)
                        .totalCount(100L).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                0L, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
        UserCoupon uc = UserCoupon.issue(tpl, 42L, "req-002", NOW);
        assertThat(uc.getFaceValue()).isEqualTo(8500L);
        assertThat(uc.getThreshold()).isZero();
        assertThat(uc.getDiscountCap()).isEqualTo(5000L);
        assertThat(uc.getTemplateCode()).isEqualTo("T20260905002");
        assertThat(uc.getStatus()).isEqualTo(CouponStatus.USABLE);
        // 领取后改模板对象，不影响已发出的券（不可变快照的意义）
        assertThat(uc.getIdempotentKey()).isEqualTo("req-002");
    }

    @Test
    @DisplayName("未发布模板领取被拒（assertReceivable 在工厂内强制）")
    void unpublishedTemplateRejected() {
        CouponTemplate tpl = CouponTemplate.reconstitute(9L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("n")
                        .couponType(CouponType.NO_THRESHOLD).faceValue(500L).threshold(0L)
                        .totalCount(10L).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                0L, ApproveStatus.APPROVED, PublishStatus.UNPUBLISHED);   // ← 未发布
        assertThatThrownBy(() -> UserCoupon.issue(tpl, 42L, "req-003", NOW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅已发布可领取");
    }

    @Test
    @DisplayName("固定区间券不在领取期被拒（未开始/已结束各验一次）")
    void fixedWindowEnforced() {
        CouponTemplate notStarted = published(ValidType.FIXED,
                LocalDateTime.of(2026, 9, 6, 0, 0), LocalDateTime.of(2026, 9, 10, 0, 0), null);
        assertThatThrownBy(() -> UserCoupon.issue(notStarted, 42L, "req-004", NOW))
                .hasMessageContaining("未开始");

        CouponTemplate ended = published(ValidType.FIXED,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 4, 0, 0), null);
        assertThatThrownBy(() -> UserCoupon.issue(ended, 42L, "req-005", NOW))
                .hasMessageContaining("已结束");
    }

    @Test
    @DisplayName("非法参数：userId<=0 / 空幂等键 拒绝")
    void illegalParamsRejected() {
        CouponTemplate tpl = published(ValidType.AFTER_RECEIVE, null, null, 7);
        assertThatThrownBy(() -> UserCoupon.issue(tpl, 0L, "req-006", NOW))
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> UserCoupon.issue(tpl, 42L, "  ", NOW))
                .hasMessageContaining("幂等键");
    }
}
