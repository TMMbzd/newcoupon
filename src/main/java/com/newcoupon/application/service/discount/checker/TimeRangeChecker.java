package com.newcoupon.application.service.discount.checker;

import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.model.discount.OrderContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 时间范围校验器（责任链第 1 环，@Order(100)）：券是否在可用时间窗内。
 *
 * <p>规则：validStart（领取时间，领取即生效）<= now <= validEnd（领取时已固化为当日 23:59:59）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>不依赖系统时间——now 统一从 {@link OrderContext} 获取（引擎层可注入固定时间，测试无定时炸弹）；</li>
 *   <li>失败文案分两种（"未到使用时间"/"已过期"）——对用户可操作：前者可以等，后者只能换券。</li>
 * </ul>
 */
@Component
@Order(100)
public class TimeRangeChecker implements EligibilityChecker {

    @Override
    public Result<Void> check(UserCoupon coupon, OrderContext context) {
        LocalDateTime now = context.getNow();
        if (now == null) {
            throw new IllegalArgumentException("OrderContext.now 不能为空");
        }

        LocalDateTime validStart = coupon.getReceiveTime();
        LocalDateTime validEnd = coupon.getValidEnd();

        if (now.isBefore(validStart)) {
            return Result.fail(ErrorCode.COUPON_NOT_USABLE, "券未到使用时间");
        }
        if (now.isAfter(validEnd)) {
            return Result.fail(ErrorCode.COUPON_NOT_USABLE, "券已过期");
        }
        return Result.success();
    }
}
