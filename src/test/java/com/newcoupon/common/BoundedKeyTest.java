package com.newcoupon.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L39（2026-09-30）：有界幂等键。
 *
 * <p>红灯背景：DTO 允许 requestId/orderNo ≤64，拼进 event_no/usage_no 后超过列宽 VARCHAR(64)，
 * 合法输入让事务报 "Data too long"（未处理异常）。本类钉住三条性质：
 * **不超长、确定性（同输入同输出）、不同输入不同键**。
 */
class BoundedKeyTest {

    @Test
    @DisplayName("短输入原样保留（人可读）")
    void shortInputKept() {
        assertThat(BoundedKey.of("RCV:", "9001:req-1")).isEqualTo("RCV:9001:req-1");
        assertThat(BoundedKey.usageNo("ORD-1", 123L)).isEqualTo("ORD-1:123");
    }

    @Test
    @DisplayName("超长输入折叠且有界：≤64 字符")
    void longInputFoldedAndBounded() {
        String longReq = "x".repeat(64);
        String no = BoundedKey.of("RCV:", "9001:" + longReq);
        assertThat(no).hasSizeLessThanOrEqualTo(BoundedKey.MAX_LEN);
        assertThat(no).startsWith("RCV:H:");

        String usage = BoundedKey.usageNo("o".repeat(64), 123L);
        assertThat(usage).hasSizeLessThanOrEqualTo(BoundedKey.MAX_LEN);
        assertThat(usage).startsWith("H:").endsWith(":123");
    }

    @Test
    @DisplayName("确定性：同输入必得同键（幂等索引撞得住重放）")
    void deterministic() {
        String a = BoundedKey.of("LCK:", "u".repeat(64));
        String b = BoundedKey.of("LCK:", "u".repeat(64));
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("不同输入得到不同键（不是截断撞车）")
    void distinctInputsDistinctKeys() {
        String a = "a".repeat(64);
        String b = "a".repeat(63) + "b";
        assertThat(BoundedKey.of("RCV:", a)).isNotEqualTo(BoundedKey.of("RCV:", b));
        assertThat(BoundedKey.usageNo(a, 1L)).isNotEqualTo(BoundedKey.usageNo(b, 1L));
    }
}
