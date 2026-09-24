package com.newcoupon.application.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模板本地缓存（P2c，清偿 L6）——只服务**领券热路径**的模板读。
 *
 * <p>为什么是独立 reader 而不是包住整个 TemplateRepository：系统里存在**必须新鲜**的模板读
 * （扣账拒绝分类的"事务外新快照"、巡检的守恒对照）——无差别缓存会把它们喂成旧照片误诊。
 * 只有领券入口 ①这一处（每请求一次主键查，模板基本不变）值得缓存；其余路径维持直读。
 *
 * <p>三兄弟防护：
 * <ul>
 * <li>**防击穿**（热 key 过期瞬间千军回源）：refreshAfterWrite——越过刷新线后第一个到达的线程
 *     独自回源，并发其余线程**拿旧值立即返回**（模板读旧 1 个刷新周期无害：限领微调最多延迟生效）。</li>
 * <li>**防穿透**（不存在的 id 恶意打探测）：查无此模板记**负缓存**（空条目），短 TTL（默认 60s
 *     +抖动）——重复探测不透到数据库；真建了这个 id 的券也最多 60 秒后可领。</li>
 * <li>**防雪崩**（同时装载的模板同时过期，回源洪峰）：每条目 TTL 独立抖动 ±20%。</li>
 * </ul>
 *
 * <p>**一致性**：create/publish 主动 {@link #invalidate(long)}（大促 0 点发布必须立刻可见，
 * 不能等 TTL）；多实例的缓存广播刷新通道按路线排在 P5-⑧（当前单实例本地失效已足够，
 * TTL 是多实例场景的兜底上界）。
 *
 * <p>度量：命中/未命中计数进日志（2e 接 Micrometer 后改为指标）。
 */
@Component
public class CachedTemplateReader {

    private static final Logger log = LoggerFactory.getLogger(CachedTemplateReader.class);

    private static final long MAX_ENTRIES = 10_000;

    private final TemplateRepository delegate;

    /** 条目值：正缓存=Optional.of(模板)；负缓存=Optional.empty()。Expiry 按正负分别定 TTL */
    private final LoadingCache<Long, Optional<CouponTemplate>> cache;

    public CachedTemplateReader(TemplateRepository delegate,
                                @Value("${coupon.template-cache.ttl-ms:300000}") long ttlMillis,
                                @Value("${coupon.template-cache.refresh-ms:200000}") long refreshMillis,
                                @Value("${coupon.template-cache.negative-ttl-ms:60000}") long negativeTtlMillis) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .executor(java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                    Thread t = new Thread(r, "template-cache-refresh");
                    t.setDaemon(true);
                    return t;
                }))   // P2 终审：默认 commonPool 上跑阻塞 JDBC 会拖累全 JVM 并行流
                .refreshAfterWrite(refreshMillis, TimeUnit.MILLISECONDS)   // 防击穿：越线后单人回源，余者取旧值
                .recordStats()
                .expireAfter(new Expiry<Long, Optional<CouponTemplate>>() {
                    @Override
                    public long expireAfterCreate(Long key, Optional<CouponTemplate> value, long now) {
                        return now + TimeUnit.NANOSECONDS.convert(jitter(ttlFor(value)), TimeUnit.MILLISECONDS);
                    }
                    @Override
                    public long expireAfterUpdate(Long key, Optional<CouponTemplate> value,
                                                  long now, long previousDuration) {
                        return now + TimeUnit.NANOSECONDS.convert(jitter(ttlFor(value)), TimeUnit.MILLISECONDS);
                    }
                    @Override
                    public long expireAfterRead(Long key, Optional<CouponTemplate> value,
                                                long now, long currentDuration) {
                        return currentDuration;   // 读不续命
                    }
                    /** 防雪崩：±20% 抖动（毫秒），打散同时装载条目的过期时刻 */
                    private long jitter(long baseMillis) {
                        return Math.max(1, baseMillis + ThreadLocalRandom.current().nextLong(
                                -(baseMillis / 5), baseMillis / 5 + 1));
                    }
                    /** 防穿透：负缓存独立短 TTL */
                    private long ttlFor(Optional<CouponTemplate> value) {
                        return value.isPresent() ? ttlMillis : negativeTtlMillis;
                    }
                })
                .build(this::load);
    }

    /** 读模板：命中/负命中不触库；越刷新线单人回源 */
    public Optional<CouponTemplate> get(long templateId) {
        Optional<CouponTemplate> v = cache.get(templateId);
        if (log.isDebugEnabled()) {
            log.debug("template cache: id={} hit? {} (stats={})", templateId,
                    v != null, cache.stats().hitCount());
        }
        return v == null ? Optional.empty() : v;
    }

    /** 主动失效（create/publish 调用——大促发布必须立刻可见） */
    public void invalidate(long templateId) {
        cache.invalidate(templateId);
        log.info("template cache invalidated: id={}", templateId);
    }

    private Optional<CouponTemplate> load(Long templateId) {
        Optional<CouponTemplate> loaded = delegate.findById(templateId);
        log.debug("template cache MISS→load: id={} found={}", templateId, loaded.isPresent());
        return loaded;
    }
}
