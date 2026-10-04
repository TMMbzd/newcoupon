package com.newcoupon.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CacheTtl} 的契约（S2 第 10 步 P2）。
 *
 * <p><b>为什么这个"小工具类"值得单测</b>：它存在的唯一理由就是修掉一个**被抄了三遍的错**——
 * {@code Expiry} 方法返回的必须是**持续时长**，不是绝对时刻。这个错误一旦重现，
 * 症状是"缓存永不硬过期"（防雪崩与负缓存短 TTL 静默失效），**极难在功能测试里被发现**。
 * 所以要在最靠近错误的地方钉一条断言：返回值必须是"时长"量级。
 *
 * <p>本项目还有一条相关教训：三处重复的代码错了三遍——<b>消除重复本身就是防再犯</b>，
 * 而"唯一实现"需要有人守，否则它和散落三处的版本一样会被改坏。
 */
class CacheTtlTest {

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    @DisplayName("返回值是「时长」：落在基准值 ±20% 的抖动区间内")
    void returnsDurationWithinJitterBand() {
        for (int i = 0; i < 100; i++) {   // 抖动是随机的，多跑几次把区间覆盖住
            assertThat(CacheTtl.jitteredNanos(1000))
                    .as("1 秒的基准值，抖动 ±20% → [800ms, 1200ms]")
                    .isBetween(ms(800), ms(1200));
        }
    }

    @Test
    @DisplayName("**绝不是「时刻」**：返回值不会跳到 System.nanoTime() 那个量级（回归防线）")
    void isDurationNotTimestamp() {
        // 原始 bug 是写成 now + 时长：now 是 System.nanoTime()，在已运行的 JVM 里
        // 至少是"秒级"起步、通常是"小时级"——量级差了三四个数量级。
        // 这条断言就是那个 bug 的墓碑。
        long nanos = CacheTtl.jitteredNanos(1000);

        assertThat(nanos)
                .as("若这里出现一个巨大的数，说明又有人把「时刻」当成「时长」返回了")
                .isLessThan(TimeUnit.SECONDS.toNanos(10));
        assertThat(nanos)
                .as("也不能小到失去意义（1ms 的基准值抖动后仍应 ≥1ms）")
                .isGreaterThanOrEqualTo(ms(1));
    }

    @Test
    @DisplayName("抖动真的在抖：多次调用不是同一个值（防雪崩的前提）")
    void actuallyJitters() {
        long first = CacheTtl.jitteredNanos(10_000);
        boolean sawDifferent = false;
        for (int i = 0; i < 50 && !sawDifferent; i++) {
            sawDifferent = CacheTtl.jitteredNanos(10_000) != first;
        }
        assertThat(sawDifferent)
                .as("基准 10 秒、抖动 ±2 秒，50 次里必然出现不同值——"
                        + "若恒等，说明抖动被写死了，防雪崩就是句空话")
                .isTrue();
    }

    @Test
    @DisplayName("退化输入不产生 0 或负数 TTL（0 表示「立即过期」或「永不过期」，看库而定，都危险）")
    void degenerateInputsStayPositive() {
        assertThat(CacheTtl.jitteredNanos(0)).isGreaterThan(0);
        assertThat(CacheTtl.jitteredNanos(1)).isGreaterThan(0);
        assertThat(CacheTtl.jitteredNanos(4)).isGreaterThan(0);
    }
}
