package com.newcoupon.application.service;

/**
 * 桶路由器（P2b v2 §3/§11——骨架先行：route() 纯函数第 1 步即真实实现，
 * 耗尽切换与开桶单飞在第 4 步补齐）。
 *
 * <p>route() 是公开纯函数（USAB-10 裁决）：无状态、无 Redis 依赖——
 * 测试可预计算桶号直连桶键断言，红灯用例有程序化前置手段。
 *
 * <p>散列用 Fibonacci hashing（乘 2^64/φ 再取高位）——裸 {@code userId % n}
 * 在连续 userId（发号器产物）下会连续落同桶，乘法散列把邻近输入打散。
 */
public final class StockBucketRouter {

    private StockBucketRouter() {
    }

    /**
     * 用户 → 桶号（同用户恒同桶——限领计数天然隔离，v2 §3）。
     *
     * @throws IllegalArgumentException bucketCount&lt;1（总监盲区⑦：模零除法全站 500 防护）
     */
    public static int route(long userId, int bucketCount) {
        if (bucketCount < 1) {
            throw new IllegalArgumentException("bucketCount must be >= 1 (防模零：coupon.sharding.bucket-count)");
        }
        long h = userId * 0x9E3779B97F4A7C15L;   // 2^64/φ 黄金比例散列
        h ^= (h >>> 32);                          // 高低位混合，放大小差异
        return (int) Long.remainderUnsigned(h, bucketCount);
    }
}
