package com.newcoupon.domain.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** V28 跨租户幂等键的配套约束：事件编号也必须带 appId。 */
class CouponEventTest {

    @Test
    @DisplayName("同一 user+requestId、不同 appId 的发放事件编号不能相同")
    void issuedEventNoIncludesTenant() {
        LocalDateTime now = LocalDateTime.now();
        CouponEvent appA = CouponEvent.issued(coupon("app-a", now), now);
        CouponEvent appB = CouponEvent.issued(coupon("app-b", now), now);

        assertThat(appA.getEventNo()).isNotEqualTo(appB.getEventNo());
        assertThat(appA.getEventNo()).contains("app-a");
        assertThat(appB.getEventNo()).contains("app-b");
    }

    private static UserCoupon coupon(String appId, LocalDateTime now) {
        return UserCoupon.reconstitute(1L, 100L, 9L, "T9",
                1000L, 1000L, null, now.plusDays(1), CouponStatus.USABLE,
                now, "same-request-id", 1, appId);
    }
}
