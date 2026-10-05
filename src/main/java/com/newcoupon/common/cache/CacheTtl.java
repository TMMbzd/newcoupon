package com.newcoupon.common.cache;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 缓存 TTL 的计算（基准值 ±20% 抖动）——**全项目唯一实现**（S2 第 10 步 P2）。
 *
 * <p><b>为什么要有这个类</b>：同一段 Expiry 代码在本项目被抄了三遍
 * （{@code CachedTemplateReader} / {@code CachedOpenAppReader} / {@code CachedTenantConfigService}），
 * <b>三遍都写错了同一个地方</b>——{@code expireAfterCreate} 的契约是返回<b>持续时长</b>
 * （Caffeine 内部自己会加 {@code now}），而三处都写成了 {@code return now + 时长}，
 * 于是条目实际存活时间 ≈ <b>创建那一刻的 {@code nanoTime} 值 + TTL</b>。
 * {@code System.nanoTime()} 是单调时钟、起点任意（Linux 上通常是开机时刻），
 * 所以这个值可能是"开机至今"的纳秒数——机器开了 10 天才启动 JVM，缓存项就会活约 10 天；
 * 而且**同一个 JVM 内越晚创建的条目活得越久**（因为 nanoTime 一直在涨）。
 *
 * <p>后果：号称"防雪崩"的 TTL 抖动与"防穿透"的负缓存短 TTL <b>从写下的第一天起就没生效过</b>
 * （抖动被 {@code now} 这个巨大的基数淹没了）。
 *
 * <p>发现方式值得记一笔：不是靠读代码看出来的，是审查 agent 留下的探针
 * <b>实测</b>出来的——TTL 设 1000ms 的缓存，跑到 t+4400ms 时回源计数仍然是 1。
 *
 * <p>所以修法不只是"改对三行"，而是<b>消除重复</b>：留一份实现，
 * 将来不会再有第四处写错。
 */
public final class CacheTtl {

    private CacheTtl() {
    }

    /**
     * 计算带抖动的 TTL，返回 Caffeine {@link com.github.benmanes.caffeine.cache.Expiry}
     * 要求的**纳秒持续时长**。
     *
     * <p><b>务必注意返回的是"时长"而不是"时刻"</b>——这正是本项目踩过的坑。
     * 直接把它当作 {@code expireAfterCreate} 的返回值即可，不要再加 {@code now}。
     *
     * @param baseMillis 基准存活时长（毫秒）；小于 5 时抖动区间会退化，由 {@code Math.max(1, …)} 兜底
     */
    public static long jitteredNanos(long baseMillis) {
        long jittered = Math.max(1, baseMillis + ThreadLocalRandom.current().nextLong(
                -(baseMillis / 5), baseMillis / 5 + 1));
        return TimeUnit.NANOSECONDS.convert(jittered, TimeUnit.MILLISECONDS);
    }
}
