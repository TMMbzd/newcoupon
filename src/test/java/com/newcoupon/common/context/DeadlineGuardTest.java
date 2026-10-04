package com.newcoupon.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 裁剪判据的**纯函数单测**（S2 第 9 步）。
 *
 * <p>对抗性审查指出：这些边界（0 / 地板值 / 负数 / 哨兵）在集成层**根本无法精确断言**——
 * 它们全都和线程调度、机器快慢赛跑。把判据抽成接 {@code nowMs} 的纯函数之后，
 * 边界才第一次变成"可以钉死的事实"。
 */
class DeadlineGuardTest {

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /** 固定的"现在"——边界断言不碰墙钟，这是本类存在的全部意义 */
    private static final long NOW = 1_000_000L;

    /** deadline 相对固定的 NOW 设，剩余时间就是确定值 */
    private RequestContext ctxWithRemaining(long remainingMs) {
        RequestContext ctx = new RequestContext("app-1", "trace", NOW + remainingMs, true);
        RequestContext.set(ctx);
        return ctx;
    }

    @Test
    @DisplayName("边界：剩余 0 算已过期、剩余 1 不算（<=0 与 >0 的分界）")
    void expired_boundary() {
        assertThat(DeadlineGuard.expired(ctxWithRemaining(1), NOW)).as("剩余 1 毫秒：还没过期").isFalse();
        assertThat(DeadlineGuard.expired(ctxWithRemaining(0), NOW)).as("剩余 0：已过期（<= 0）").isTrue();
        assertThat(DeadlineGuard.expired(ctxWithRemaining(-1), NOW)).as("负数：已过期").isTrue();
    }

    @Test
    @DisplayName("边界：地板判定是「严格小于」——恰好等于地板值时放行")
    void belowFloor_boundary() {
        assertThat(DeadlineGuard.belowFloor(ctxWithRemaining(500), NOW, 500))
                .as("剩余 500、地板 500：不拦（严格小于）").isFalse();
        assertThat(DeadlineGuard.belowFloor(ctxWithRemaining(500), NOW, 501))
                .as("剩余 500、地板 501：拦").isTrue();
        assertThat(DeadlineGuard.belowFloor(ctxWithRemaining(500), NOW, DeadlineGuard.RETRY_FLOOR_MS))
                .as("剩余 500 < 重试地板 1200：拦").isTrue();
        assertThat(DeadlineGuard.belowFloor(ctxWithRemaining(DeadlineGuard.RETRY_FLOOR_MS), NOW,
                DeadlineGuard.RETRY_FLOOR_MS)).as("恰好等于地板：放行").isFalse();
    }

    @Test
    @DisplayName("哨兵：无预算（EMPTY/内部面）永不判过期、永不撞地板")
    void noDeadline_neverTrips() {
        assertThat(RequestContext.current().hasDeadline())
                .as("不设上下文时就是 EMPTY，没有预算").isFalse();
        assertThat(DeadlineGuard.expired(RequestContext.current(), NOW)).isFalse();
        assertThat(DeadlineGuard.belowFloor(RequestContext.current(), NOW, Long.MAX_VALUE / 2)).isFalse();

        // 显式用 MAX_VALUE 构造也一样（这正是"不能拿哨兵做算术"的那条约束）
        RequestContext maxCtx = ctxWithRemaining(Long.MAX_VALUE - NOW);
        assertThat(DeadlineGuard.expired(maxCtx, NOW)).isFalse();
        assertThat(DeadlineGuard.belowFloor(maxCtx, NOW, 1_200)).isFalse();
    }

    @Test
    @DisplayName("requireBudget：过期时抛专属可重试码 5003（不是 5001）")
    void requireBudget_throwsDedicatedCode() {
        ctxWithRemaining(-60_000);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(DeadlineGuard::requireBudget))
                .isInstanceOf(com.newcoupon.common.BizException.class)
                .extracting(e -> ((com.newcoupon.common.BizException) e).getErrorCode().getCode())
                .isEqualTo(5003);
    }
}
