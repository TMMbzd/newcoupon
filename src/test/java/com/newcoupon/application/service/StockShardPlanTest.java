package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C1 分片纯计算合同（ADR-0018）：
 * 有效片数、额度分配和路由必须只有一份实现，发布建行与领券路由都调用它。
 */
class StockShardPlanTest {

    @Test
    @DisplayName("有效片数：total 小于配置片数时只建 total 行，避免 0 配额行")
    void effectiveShards_minOfConfiguredAndTotal() {
        assertThat(StockShardPlan.effectiveShards(16, 100_000)).isEqualTo(16);
        assertThat(StockShardPlan.effectiveShards(16, 10)).isEqualTo(10);
        assertThat(StockShardPlan.effectiveShards(16, 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("额度：前 remainder 片多 1，总和严格等于 total")
    void quotas_sumToTotal() {
        long[] q = StockShardPlan.quotas(8, 1003);
        assertThat(q).hasSize(8);
        assertThat(java.util.Arrays.stream(q).sum()).isEqualTo(1003);
        // 1003 / 8 = 125 余 3 → 前 3 片 126，后 5 片 125
        assertThat(q[0]).isEqualTo(126);
        assertThat(q[1]).isEqualTo(126);
        assertThat(q[2]).isEqualTo(126);
        assertThat(q[3]).isEqualTo(125);
        assertThat(q[7]).isEqualTo(125);
    }

    @Test
    @DisplayName("路由：floorMod 对连续 userId 也均匀落片，且结果在 0..n-1")
    void route_isBoundedAndDeterministic() {
        for (long userId = 1; userId <= 100; userId++) {
            int shard = StockShardPlan.route(userId, 16);
            assertThat(shard).isBetween(0, 15);
        }
        assertThat(StockShardPlan.route(16, 16)).isEqualTo(0);
        assertThat(StockShardPlan.route(17, 16)).isEqualTo(1);
    }

    @Test
    @DisplayName("非法输入：片数/总量为 0 时直接拒绝，避免除零和 0 配额")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> StockShardPlan.effectiveShards(0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockShardPlan.effectiveShards(16, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockShardPlan.quotas(8, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockShardPlan.route(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
