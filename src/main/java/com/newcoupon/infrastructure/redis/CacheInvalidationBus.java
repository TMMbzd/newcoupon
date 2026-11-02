package com.newcoupon.infrastructure.redis;

import com.newcoupon.common.cache.CacheInvalidationPort;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 缓存跨实例失效总线：Redis pub/sub 即时通知 + 版本键轮询兜底 + TTL 最终上界。
 *
 * <p><b>消息契约</b>：{@code namespace|base64url(key)|instanceId}，频道
 * {@code c:cache:invalidate}（可用 {@code coupon.cache.invalidation.channel} 覆盖）。
 * key 做 Base64URL 编码，避免 appId 等业务键里出现分隔符把协议打乱；instanceId 用于
 * 忽略自己发出的消息（本实例的本地失效由缓存自己在广播前完成，不需要二次清除）。
 *
 * <p><b>为什么还要版本键</b>：Redis pub/sub 是 at-most-once——订阅连接闪断、实例恰好在
 * 重启、消息在客户端队列里丢失，都会让某个实例永远收不到那条失效。发布方在 PUBLISH
 * <b>之前</b>对 {@code c:cache:ver:{namespace}} 做 INCR；每个实例按
 * {@code coupon.cache.invalidation.poll-ms}（默认 5s）读一次版本号，发现落后就对该
 * namespace 做一次全量失效（粗粒度但正确）。版本键读取逐个 GET，不用 MGET——MGET
 * 在 Redis Cluster 里跨 slot 会直接报 CROSSSLOT。
 *
 * <p><b>失败立场</b>：发布/轮询任何 Redis 异常都只 WARN，不向业务抛出。缓存失效是
 * "尽快收敛"而不是"写事务的一部分"；Redis 整体故障时 TTL 仍是上界，认证面另有
 * nonce fail-closed 防守。
 *
 * <p><b>开关</b>：{@code coupon.cache.invalidation.enabled=false} 时退化为纯本地失效 +
 * TTL（用于排查 pub/sub 干扰；监听容器也不会装配）。
 */
@Component
public class CacheInvalidationBus implements CacheInvalidationPort {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationBus.class);

    static final String DEFAULT_CHANNEL = "c:cache:invalidate";
    private static final String VERSION_KEY_PREFIX = "c:cache:ver:";

    private final StringRedisTemplate redis;

    /** namespace → 本地失效动作；注册发生在三个缓存的构造期（单线程启动），之后只读。 */
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    /** namespace → 上次见到的版本号；首次轮询只建基线，不触发全量失效。 */
    private final Map<String, Long> lastSeenVersions = new ConcurrentHashMap<>();

    @Value("${coupon.cache.invalidation.enabled:true}")
    private boolean enabled = true;

    @Value("${coupon.cache.invalidation.channel:c:cache:invalidate}")
    private String channel = DEFAULT_CHANNEL;

    @Value("${coupon.cache.invalidation.instance-id:${random.uuid}}")
    private String instanceId = UUID.randomUUID().toString();

    @Autowired
    public CacheInvalidationBus(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 测试/同 JVM 第二实例构造（不经过 Spring 属性注入）。 */
    public CacheInvalidationBus(StringRedisTemplate redis, String channel, String instanceId, boolean enabled) {
        this.redis = redis;
        this.channel = channel;
        this.instanceId = instanceId;
        this.enabled = enabled;
    }

    public String instanceId() {
        return instanceId;
    }

    @Override
    public void invalidate(String namespace, String key) {
        if (!enabled || namespace == null || key == null) {
            return;
        }
        try {
            // 先推进版本号再发布：即使 PUBLISH 丢失，轮询方也能从版本差发现"有变更"。
            redis.opsForValue().increment(versionKey(namespace));
            redis.convertAndSend(channel, payload(namespace, key));
            log.debug("缓存失效已广播：namespace={} key={} channel={}", namespace, key, channel);
        } catch (RuntimeException e) {
            log.warn("缓存失效广播失败（本实例本地失效已生效，版本键/TTL 兜底）：namespace={} key={} err={}",
                    namespace, key, e.toString());
        }
    }

    @Override
    public void register(String namespace, Consumer<String> invalidateKey, Runnable invalidateAll) {
        registrations.put(namespace, new Registration(invalidateKey, invalidateAll));
    }

    /** 供监听容器调用；消息格式非法/未知 namespace 都只忽略，不影响其他消息。 */
    public void onMessage(String body) {
        if (!enabled || body == null) {
            return;
        }
        String[] parts = body.split("\\|", 3);
        if (parts.length != 3) {
            log.warn("缓存失效消息格式非法，已忽略：{}", body);
            return;
        }
        if (instanceId.equals(parts[2])) {
            return;   // 自己发的消息：本地已失效，忽略避免重复回源
        }
        String namespace = parts[0];
        String key;
        try {
            key = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("缓存失效消息 key 编码非法，已忽略：{}", body);
            return;
        }
        Registration registration = registrations.get(namespace);
        if (registration == null) {
            log.debug("缓存失效消息的 namespace 未注册，已忽略：namespace={}", namespace);
            return;
        }
        try {
            registration.invalidateKey().accept(key);
            log.debug("收到跨实例缓存失效：namespace={} key={} from={}", namespace, key, parts[2]);
        } catch (RuntimeException e) {
            log.warn("跨实例缓存失效执行失败：namespace={} key={} err={}", namespace, key, e.toString());
        }
    }

    /**
     * 版本键轮询：发现版本变化但没有收到 pub/sub 时，做一次 namespace 级全量失效。
     * 首次看到某 namespace 只建基线（本实例缓存此时本来就从空开始）。
     */
    @Scheduled(
            fixedDelayString = "${coupon.cache.invalidation.poll-ms:5000}",
            initialDelayString = "${coupon.cache.invalidation.poll-ms:5000}")
    public void pollOnce() {
        if (!enabled) {
            return;
        }
        for (Map.Entry<String, Registration> entry : registrations.entrySet()) {
            String namespace = entry.getKey();
            Long current;
            try {
                current = readVersion(namespace);
            } catch (RuntimeException e) {
                log.warn("缓存版本号读取失败（本轮跳过，TTL 仍兜底）：namespace={} err={}",
                        namespace, e.toString());
                continue;
            }
            Long last = lastSeenVersions.get(namespace);
            if (last == null) {
                lastSeenVersions.put(namespace, current == null ? 0L : current);
                continue;
            }
            long normalized = current == null ? 0L : current;
            if (normalized == last) {
                continue;
            }
            lastSeenVersions.put(namespace, normalized);
            try {
                entry.getValue().invalidateAll().run();
                log.warn("缓存版本号变化但未收到广播，已全量失效：namespace={} last={} current={}",
                        namespace, last, normalized);
            } catch (RuntimeException e) {
                log.warn("版本兜底全量失效执行失败：namespace={} err={}", namespace, e.toString());
            }
        }
    }

    // ------------------------------------------------------------------

    private Long readVersion(String namespace) {
        String raw = redis.opsForValue().get(versionKey(namespace));
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            log.warn("缓存版本键不是数字，按缺失处理：namespace={} value={}", namespace, raw);
            return null;
        }
    }

    private String payload(String namespace, String key) {
        String encodedKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return namespace + "|" + encodedKey + "|" + instanceId;
    }

    private String versionKey(String namespace) {
        return VERSION_KEY_PREFIX + namespace;
    }

    private record Registration(Consumer<String> invalidateKey, Runnable invalidateAll) {
    }
}
