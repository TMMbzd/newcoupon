package com.newcoupon.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 控制器入参分类（2026-10-18 对抗审查发现：非法 status 会抛 IllegalArgumentException，
 * 被全局兜底成 5000，而不是参数错误 1000）。
 */
class TradeControllerTest {

    @Test
    @DisplayName("非法 status 是参数错误 1000，不是系统错误 5000")
    void invalidStatusIsParamError() {
        TradeController controller = new TradeController(null);

        assertThatThrownBy(() -> controller.myCoupons(1L, 9))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }
}
