package com.newcoupon.application.service.discount.checker;

import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 券状态校验器（责任链第 2 环，@Order(200)）：只有"待使用"的券可参与计算。
 *
 * <p>设计约束：只认 USABLE，其余全拒（锁定/已核销/已过期/已作废）——
 * 不依赖终态枚举特性做排除，避免"新增状态后漏判"。
 */
@Component
@Order(200)
public class StatusChecker implements EligibilityChecker {

    @Override
    public Result<Void> check(UserCoupon coupon, OrderContext context) {
        CouponStatus status = coupon.getStatus();
        if (status == CouponStatus.USABLE) {
            return Result.success();
        }
        return Result.fail(ErrorCode.COUPON_NOT_USABLE, "券状态不可用：" + status.getDesc());
    }
}
