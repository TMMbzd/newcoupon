package com.newcoupon.domain.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponStatus;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 状态机迁移表驱动测试——"测试即需求文档"（docs/00 第 9 节）：
 * 状态机的每一条合法/非法迁移都用用例固化。改迁移表必改测试，两边不一致 CI 直接红。
 *
 * <p>规范：纯 JUnit 单测，不起 Spring 容器、不连任何中间件，秒级运行。
 */
@DisplayName("券状态机迁移表")
class CouponStateMachineTest {

    // ---------- 合法迁移：CouponTransition 的每一行都必须可通行 ----------

    @ParameterizedTest
    @EnumSource(CouponTransition.class)
    @DisplayName("每条声明的迁移：transit(from, t) 返回声明的目标状态")
    void declaredTransitionsAreValid(CouponTransition t) {
        CouponStatus target = CouponStateMachine.transit(t.getFrom(), t);
        assertThat(target).isEqualTo(t.getTo());
        if (t.getFrom() != null) {
            assertThat(CouponStateMachine.canTransit(t.getFrom(), t.getTo())).isTrue();
        }
    }

    // ---------- 非法迁移：资损级红线，一条都不能放过 ----------

    @Test
    @DisplayName("终态无出边：USED/EXPIRED/INVALID 不能迁移到任何状态")
    void terminalStatesHaveNoOutgoingEdges() {
        for (CouponStatus terminal : new CouponStatus[]{CouponStatus.USED, CouponStatus.EXPIRED, CouponStatus.INVALID}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (CouponStatus any : CouponStatus.values()) {
                assertThat(CouponStateMachine.canTransit(terminal, any))
                        .as("终态 %s 不应能迁移到 %s", terminal, any)
                        .isFalse();
            }
            for (CouponTransition t : CouponTransition.values()) {
                assertThatThrownBy(() -> CouponStateMachine.transit(terminal, t))
                        .as("终态 %s 执行动作 %s 必须抛冲突", terminal, t)
                        .isInstanceOf(BizException.class)
                        .extracting(e -> ((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT);
            }
        }
    }

    @Test
    @DisplayName("跨状态直跳非法：USABLE 不能直接 USED（必须先 LOCK），null 不能直接 LOCKED（必须先领券）")
    void skipStateAndUnbornAreIllegal() {
        assertThat(CouponStateMachine.canTransit(CouponStatus.USABLE, CouponStatus.USED)).isFalse();
        assertThat(CouponStateMachine.canTransit(CouponStatus.USABLE, CouponStatus.EXPIRED)).isTrue();   // 到期是合法的
        assertThat(CouponStateMachine.canTransit(CouponStatus.USABLE, CouponStatus.INVALID)).isTrue();  // 作废是合法的
        assertThatThrownBy(() -> CouponStateMachine.transit(null, CouponTransition.LOCK))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("重复核销被拒：LOCKED->CONFIRM 只能走一次，USED 再 CONFIRM 抛冲突（一券两用防线）")
    void doubleConfirmRejected() {
        CouponStatus first = CouponStateMachine.transit(CouponStatus.LOCKED, CouponTransition.CONFIRM);
        assertThat(first).isEqualTo(CouponStatus.USED);
        assertThatThrownBy(() -> CouponStateMachine.transit(CouponStatus.USED, CouponTransition.CONFIRM))
                .isInstanceOf(BizException.class);
    }

    // ---------- 全矩阵完整性：枚举表与 canTransit 集合互为镜像 ----------

    @Test
    @DisplayName("canTransit 的结果与迁移表完全一致（表驱动全矩阵核对）")
    void canTransitMatchesDeclaredTableExactly() {
        // 独立重算期望值，避免用被测代码自己验证自己
        java.util.Map<CouponStatus, Set<CouponStatus>> exp = new java.util.EnumMap<>(CouponStatus.class);
        for (CouponStatus s : CouponStatus.values()) {
            exp.put(s, EnumSet.noneOf(CouponStatus.class));
        }
        for (CouponTransition t : CouponTransition.values()) {
            if (t.getFrom() != null) {   // RECEIVE 的 from 为 null，不出现在状态对矩阵里
                exp.get(t.getFrom()).add(t.getTo());
            }
        }
        for (CouponStatus from : CouponStatus.values()) {
            for (CouponStatus to : CouponStatus.values()) {
                assertThat(CouponStateMachine.canTransit(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(exp.get(from).contains(to));
            }
        }
    }

    @Test
    @DisplayName("of() 按 DB 值还原枚举，未知值抛 IllegalArgumentException")
    void statusOfRoundTrip() {
        for (CouponStatus s : CouponStatus.values()) {
            assertThat(CouponStatus.of(s.getCode())).isEqualTo(s);
        }
        assertThatThrownBy(() -> CouponStatus.of(99)).isInstanceOf(IllegalArgumentException.class);
    }
}
