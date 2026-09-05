package com.newcoupon.domain.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.common.BizException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("占用单聚合")
class CouponUsageTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 12, 0);

    private static UserCoupon usableCoupon() {
        return UserCoupon.reconstitute(5L, 42L, 1L, "T1", 1000L, 10000L, null,
                LocalDateTime.of(2026, 9, 12, 23, 59, 59), CouponStatus.USABLE, NOW, "req-1", 1);
    }

    @Test
    @DisplayName("占用工厂：合法输入创建成功，初始状态=占用中，金额为空（核销时才写）")
    void occupyCreates() {
        CouponUsage usage = CouponUsage.occupy(usableCoupon(), "ORD-1", "ORD-1:5", NOW);
        assertThat(usage.getStatus()).isEqualTo(UsageStatus.OCCUPYING);
        assertThat(usage.getDiscountAmt()).isNull();
        assertThat(usage.getUsageNo()).isEqualTo("ORD-1:5");
        assertThat(usage.getUserCouponId()).isEqualTo(5L);
        assertThatCode(usage::checkConfirmable).doesNotThrowAnyException();
        assertThatCode(usage::checkReleasable).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非待使用的券不可占用（已锁定/已核销/已过期各拒一次）——锁券的内存快检")
    void onlyUsableCanBeOccupied() {
        for (CouponStatus st : new CouponStatus[]{CouponStatus.LOCKED, CouponStatus.USED, CouponStatus.EXPIRED}) {
            UserCoupon locked = UserCoupon.reconstitute(5L, 42L, 1L, "T1", 1000L, 10000L, null,
                    LocalDateTime.of(2026, 9, 12, 23, 59, 59), st, NOW, "req-1", 1);
            assertThatThrownBy(() -> CouponUsage.occupy(locked, "ORD-1", "ORD-1:5", NOW))
                    .as("状态 %s 应被拒绝", st)
                    .hasMessageContaining("仅待使用");
        }
    }

    @Test
    @DisplayName("空订单号/空占用单号拒绝")
    void blankOrderRejected() {
        assertThatThrownBy(() -> CouponUsage.occupy(usableCoupon(), " ", "ORD-1:5", NOW))
                .hasMessageContaining("订单号");
        assertThatThrownBy(() -> CouponUsage.occupy(usableCoupon(), "ORD-1", null, NOW))
                .hasMessageContaining("占用单号");
    }

    @Test
    @DisplayName("核销前置：已释放→拒绝；已核销→静默通过（幂等由调用方处理）")
    void confirmableRules() {
        CouponUsage released = CouponUsage.reconstitute(1L, "ORD-1:5", 42L, 5L, "ORD-1",
                UsageStatus.RELEASED, null, NOW, null, NOW);
        assertThatThrownBy(released::checkConfirmable)
                .hasMessageContaining("已释放");

        CouponUsage confirmed = CouponUsage.reconstitute(1L, "ORD-1:5", 42L, 5L, "ORD-1",
                UsageStatus.CONFIRMED, 1000L, NOW, NOW, null);
        assertThatCode(confirmed::checkConfirmable).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("释放前置：已核销→拒绝（资损红线：一张券花两次）；已释放→静默通过（幂等重放）")
    void releasableRules() {
        CouponUsage confirmed = CouponUsage.reconstitute(1L, "ORD-1:5", 42L, 5L, "ORD-1",
                UsageStatus.CONFIRMED, 1000L, NOW, NOW, null);
        assertThatThrownBy(confirmed::checkReleasable)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已核销不可释放");

        CouponUsage released = CouponUsage.reconstitute(1L, "ORD-1:5", 42L, 5L, "ORD-1",
                UsageStatus.RELEASED, null, NOW, null, NOW);
        assertThatCode(released::checkReleasable).doesNotThrowAnyException();
    }
}
