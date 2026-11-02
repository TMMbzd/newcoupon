package com.newcoupon.common.cache;

import java.util.function.Consumer;

/**
 * 缓存跨实例失效端口（Phase 1，多实例无状态）。
 *
 * <p><b>为什么需要它</b>：`CachedTemplateReader` / `CachedOpenAppReader` /
 * `CachedTenantConfigService` 的主动失效此前只清本 JVM 的 Caffeine。写操作提交后，
 * 其他实例仍会拿旧值继续服务，直到各自 TTL 到期——旧 app/secret、被停用的接入方、
 * 旧租户退款/开关策略在这段时间里仍然"有效"。
 *
 * <p><b>分层兜底</b>（实现方 {@code infrastructure.redis.CacheInvalidationBus}）：
 * <ol>
 *   <li>本实例：调用方先清本地（立即生效）；</li>
 *   <li>Redis pub/sub：广播 namespace + key，其他实例即时清对应条目；</li>
 *   <li>版本键轮询：消息丢失时，按 namespace 的版本号变化触发一次 {@code invalidateAll}；</li>
 *   <li>TTL 抖动：Redis 不可用时的最终上界。</li>
 * </ol>
 *
 * <p>接口放在 common 是为了让三个缓存不直接依赖 Redis 实现；单测传 {@link #NOOP} 即可，
 * 不启动 Spring/Redis。
 */
public interface CacheInvalidationPort {

    String NAMESPACE_TEMPLATE = "template";
    String NAMESPACE_OPEN_APP = "open-app";
    String NAMESPACE_TENANT_CONFIG = "tenant-config";

    /**
     * 广播一个 key 的失效（调用方负责本实例的本地清除）。
     * 实现必须吞掉 Redis 异常：失效是尽力而为，不能让业务写因为广播失败而回滚。
     */
    void invalidate(String namespace, String key);

    /**
     * 注册某 namespace 的本地失效动作。
     *
     * @param invalidateKey 按 key 失效单个条目（pub/sub 收到其他实例消息时调用）
     * @param invalidateAll 全量失效（版本键轮询发现"有变更但消息丢了"时调用）
     */
    void register(String namespace, Consumer<String> invalidateKey, Runnable invalidateAll);

    /** 无操作实现：单测/未接线场景，保持缓存构造器可复用。 */
    CacheInvalidationPort NOOP = new CacheInvalidationPort() {
        @Override
        public void invalidate(String namespace, String key) {
            // 无跨实例通道
        }

        @Override
        public void register(String namespace, Consumer<String> invalidateKey, Runnable invalidateAll) {
            // 无订阅
        }
    };
}
