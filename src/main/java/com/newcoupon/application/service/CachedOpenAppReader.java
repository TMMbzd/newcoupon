package com.newcoupon.application.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.newcoupon.common.crypto.SecretCipher;
import com.newcoupon.domain.repository.OpenAppRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 接入方认证信息缓存（S1）——只服务**每一次开放面调用**的鉴权读。
 *
 * <p>不缓存会怎样：每个请求 1 次应用查询 + 1 次密钥查询 + N 次 AES 解密。签名面是全站入口，
 * 这条路径上的每一次 DB 往返都会被 QPS 放大。
 *
 * <p>三兄弟防护（与 {@link CachedTemplateReader} 同一套手法，本项目第二次用）：
 * <ul>
 *   <li><b>防击穿</b>：{@code refreshAfterWrite}——越线后第一个到达的线程单独回源，其余取旧值。
 *       这里比模板缓存更宽容：接入方信息的变化（改名、调配额）晚几十秒生效没有任何代价。</li>
 *   <li><b>防穿透</b>：不存在的 appId 记**负缓存**（短 TTL）——否则拿随机 appId 打过来，
 *       每次都透到数据库（这本身就是一种低成本攻击）。</li>
 *   <li><b>防雪崩</b>：TTL ±20% 抖动——同时装载的应用不会同时过期。</li>
 * </ul>
 *
 * <p><b>一致性</b>：注册/轮换/启停后由 {@link OpenAppService} 主动 {@link #invalidate(String)}
 * ——密钥轮换必须立刻生效（旧密钥在轮换期结束的那一刻就该失效，不能等 TTL 自然过期）。
 *
 * <p><b>缓存里存的是明文 SK</b>：解密只在回源时做一次，之后命中缓存直接用。
 * 这是刻意的——每个请求都做一次 AES-GCM 解密是浪费；而进程内存里的明文密钥，
 * 其风险上界与"主密钥也在同一进程内存里"相同，不因缓存而变坏。
 */
@Component
public class CachedOpenAppReader {

    private static final Logger log = LoggerFactory.getLogger(CachedOpenAppReader.class);

    private static final long MAX_ENTRIES = 1_000;

    private final OpenAppRepository repository;
    private final SecretCipher cipher;
    private final long ttlMillis;
    private final long refreshMillis;
    private final long negativeTtlMillis;

    private final LoadingCache<String, Optional<AppAuth>> cache;

    public CachedOpenAppReader(OpenAppRepository repository,
                               SecretCipher cipher,
                               @Value("${coupon.open.auth-cache.ttl-ms:60000}") long ttlMillis,
                               @Value("${coupon.open.auth-cache.refresh-ms:30000}") long refreshMillis,
                               @Value("${coupon.open.auth-cache.negative-ttl-ms:30000}") long negativeTtlMillis) {
        this.repository = repository;
        this.cipher = cipher;
        this.ttlMillis = ttlMillis;
        this.refreshMillis = refreshMillis;
        this.negativeTtlMillis = negativeTtlMillis;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .refreshAfterWrite(refreshMillis, TimeUnit.MILLISECONDS)
                .recordStats()
                .expireAfter(new Expiry<String, Optional<AppAuth>>() {
                    @Override
                    public long expireAfterCreate(String key, Optional<AppAuth> value, long now) {
                        return now + TimeUnit.NANOSECONDS.convert(jitter(ttlFor(value)), TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Optional<AppAuth> value,
                                                  long now, long previousDuration) {
                        return now + TimeUnit.NANOSECONDS.convert(jitter(ttlFor(value)), TimeUnit.MILLISECONDS);
                    }

                    @Override
                    public long expireAfterRead(String key, Optional<AppAuth> value,
                                                long now, long currentDuration) {
                        return currentDuration;   // 读不续命
                    }

                    private long jitter(long baseMillis) {
                        return Math.max(1, baseMillis + ThreadLocalRandom.current().nextLong(
                                -(baseMillis / 5), baseMillis / 5 + 1));
                    }

                    private long ttlFor(Optional<AppAuth> value) {
                        return value.isPresent() ? ttlMillis : negativeTtlMillis;
                    }
                })
                .build(this::load);
    }

    /** @return 应用不存在时返回 {@code Optional.empty()}（负缓存命中，不触库） */
    public Optional<AppAuth> find(String appId) {
        Optional<AppAuth> cached = cache.get(appId);
        return cached == null ? Optional.empty() : cached;
    }

    /** 主动失效（注册/轮换/启停后调用——密钥轮换必须立刻生效） */
    public void invalidate(String appId) {
        cache.invalidate(appId);
        log.info("open-app auth cache invalidated: appId={}", appId);
    }

    private Optional<AppAuth> load(String appId) {
        return repository.findApp(appId).map(app -> {
            List<String> secrets = repository.findUsableSecrets(appId).stream()
                    .map(record -> cipher.decrypt(record.cipher()))
                    .toList();
            log.debug("open-app auth cache MISS→load: appId={} enabled={} usableSecrets={}",
                    appId, app.enabled(), secrets.size());
            return new AppAuth(app.appId(), app.enabled(), secrets, app.qpsLimit(), app.dailyQuota());
        });
    }

    /** 验签所需的全部信息（密钥已解密）；{@code enabled=false} 的应用照样缓存（避免反复回源） */
    public record AppAuth(String appId, boolean enabled, List<String> secrets,
                          int qpsLimit, int dailyQuota) {

        /** 当前有效的密钥版本数（诊断用） */
        public int secretVersions() {
            return secrets.size();
        }

        /** 是否还有可用于验签的密钥（轮换期未结束的旧密钥也算） */
        public boolean hasUsableSecret() {
            return !secrets.isEmpty();
        }

        /** 供日志/诊断使用，绝不返回明文 */
        @Override
        public String toString() {
            return "AppAuth{appId=" + appId + ", enabled=" + enabled
                    + ", secretVersions=" + secrets.size()
                    + ", qpsLimit=" + qpsLimit + ", dailyQuota=" + dailyQuota + '}';
        }
    }

    /** 诊断：当前缓存条目数（测试与排查用） */
    public long estimatedSize() {
        return cache.estimatedSize();
    }
}
