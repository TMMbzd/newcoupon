package com.newcoupon.domain.statemachine;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 用户券状态机：所有状态流转的**唯一入口**。
 *
 * <p>用法：业务代码先 {@link #transit(CouponStatus, CouponTransition)} 得到目标状态，
 * 再执行条件 UPDATE（WHERE status = 当前状态），影响行数为 0 说明并发冲突——
 * 状态机（内存校验）+ 条件 UPDATE（数据库校验）双层防并发。
 *
 * <p>为什么状态机是纯静态工具而不用 Spring Bean？——领域内核不依赖容器（docs/README 工程结构），
 * 单测可以直接调用，秒级运行。
 */
public final class CouponStateMachine {

    /** 合法迁移表：from -> { 可到达的状态集合 }，由 CouponTransition 枚举推导，单一事实源 */
    private static final Map<CouponStatus, Set<CouponStatus>> ALLOWED = new EnumMap<>(CouponStatus.class);

    static {
        for (CouponStatus s : CouponStatus.values()) {
            ALLOWED.put(s, EnumSet.noneOf(CouponStatus.class));
        }
        for (CouponTransition t : CouponTransition.values()) {
            // RECEIVE 的 from 为 null（领券前"券不存在"），合法迁移表只登记已存在状态的出边
            if (t.getFrom() != null) {
                ALLOWED.get(t.getFrom()).add(t.getTo());
            }
        }
    }

    private CouponStateMachine() {
    }

    /** 当前状态能否迁往目标状态 */
    public static boolean canTransit(CouponStatus from, CouponStatus to) {
        return from != null && ALLOWED.get(from).contains(to);
    }

    /**
     * 校验并执行迁移。
     *
     * @param current    券当前状态（读到的旧值）
     * @param transition 本次业务动作
     * @return 目标状态
     * @throws BizException STATE_CONFLICT 当前状态不满足该动作的前置条件（并发流转/重复操作/非法操作）
     */
    public static CouponStatus transit(CouponStatus current, CouponTransition transition) {
        if (current == null) {
            // RECEIVE 的 from 为 null（"尚不存在"），其余动作都要求券已存在
            if (transition.getFrom() == null) {
                return transition.getTo();
            }
            throw conflict(null, transition);
        }
        if (transition.getFrom() != current) {
            throw conflict(current, transition);
        }
        return transition.getTo();
    }

    private static BizException conflict(CouponStatus current, CouponTransition transition) {
        String from = current == null ? "不存在" : current.getDesc();
        return new BizException(ErrorCode.STATE_CONFLICT,
                "券当前状态=" + from + ", 动作=" + transition.getTrigger() + " 要求起点="
                        + (transition.getFrom() == null ? "不存在" : transition.getFrom().getDesc()));
    }
}
