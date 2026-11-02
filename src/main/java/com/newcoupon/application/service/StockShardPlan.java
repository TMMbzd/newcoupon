package com.newcoupon.application.service;

/**
 * C1 库存分片的纯计算规则（ADR-0018）。
 *
 * <p>把“分几片、每片多少、用户落哪片”从数据库和 Redis 里拿出来，做成无副作用的函数：
 * 发布建行、迁移校验、并发测试都调用同一份实现，避免“建行时一套算法、路由时另一套”的经典漂移。
 *
 * <p>不变量：
 * <ul>
 *   <li>有效片数 {@code n = min(配置片数, total)}；total 很小时不建 0 配额行；</li>
 *   <li>{@code quotas} 的元素和严格等于 total；前 {@code total % n} 片各多 1；</li>
 *   <li>路由 {@code floorMod(userId, n)}：userId 为正时结果 0..n-1，连续 userId 也能均匀落片。</li>
 * </ul>
 */
public final class StockShardPlan {

    private StockShardPlan() {
    }

    /** 有效分片数：不超过 total，避免建出 quota=0 的行。 */
    public static int effectiveShards(int configuredShards, long totalCount) {
        if (configuredShards < 1) {
            throw new IllegalArgumentException("configuredShards must be >= 1");
        }
        if (totalCount < 1) {
            throw new IllegalArgumentException("totalCount must be >= 1");
        }
        return (int) Math.min((long) configuredShards, totalCount);
    }

    /** 按“均分 + 前 remainder 片多 1”生成额度；返回值长度 = effectiveShards(configured,total)。 */
    public static long[] quotas(int effectiveShards, long totalCount) {
        if (effectiveShards < 1) {
            throw new IllegalArgumentException("effectiveShards must be >= 1");
        }
        if (totalCount < effectiveShards) {
            throw new IllegalArgumentException("totalCount must be >= effectiveShards");
        }
        long base = totalCount / effectiveShards;
        long remainder = totalCount % effectiveShards;
        long[] quotas = new long[effectiveShards];
        for (int i = 0; i < effectiveShards; i++) {
            quotas[i] = base + (i < remainder ? 1 : 0);
        }
        return quotas;
    }

    /** 用户的首选分片号。 */
    public static int route(long userId, int effectiveShards) {
        if (effectiveShards < 1) {
            throw new IllegalArgumentException("effectiveShards must be >= 1");
        }
        return (int) Math.floorMod(userId, (long) effectiveShards);
    }
}
