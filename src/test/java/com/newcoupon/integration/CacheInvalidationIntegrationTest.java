package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.CachedOpenAppReader;
import com.newcoupon.application.service.CachedOpenAppReader.AppAuth;
import com.newcoupon.application.service.CachedTemplateReader;
import com.newcoupon.application.service.CachedTenantConfigService;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.AppWithSecret;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TemplateLifecycleService;
import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.common.crypto.SecretCipher;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppConfigLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppConfigMapper;
import com.newcoupon.infrastructure.redis.CacheInvalidationBus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 缓存跨实例失效的真实 Redis 回归（Phase 1）。
 *
 * <p><b>怎么在单 JVM 里证明"另一个实例"</b>：手工构造第二套
 * {@link CacheInvalidationBus} + 三个缓存 reader + 独立的
 * {@link RedisMessageListenerContainer}，连同一个 Redis。第二套缓存 TTL 设得很长、
 * refresh 线更远，因此测试期间的重新回源只可能来自跨实例失效——TTL 还没来得及帮忙。
 *
 * <p>覆盖：应用密钥轮换、应用停用、租户配置变更、模板下线、pub/sub 消息丢失后由版本键
 * 轮询恢复。发布失败不拖垮业务写在 {@code CacheInvalidationBusTest} 用 mock 覆盖。
 */
class CacheInvalidationIntegrationTest extends ShardedIntegrationTestSupport {

    private static final String CHANNEL = "c:cache:invalidate";
    private static final long LONG_TTL_MS = 300_000L;
    private static final long FAR_REFRESH_MS = 600_000L;
    private static final long NEGATIVE_TTL_MS = 60_000L;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisConnectionFactory redisConnectionFactory;
    @Autowired private OpenAppService openAppService;
    @Autowired private OpenAppRepository openAppRepository;
    @Autowired private SecretCipher secretCipher;
    @Autowired private TemplateAppService templateAppService;
    @Autowired private TemplateLifecycleService templateLifecycleService;
    @Autowired private TemplateRepository templateRepository;
    @Autowired private TenantConfigService tenantConfigService;
    @Autowired private OpenAppConfigMapper configMapper;
    @Autowired private OpenAppConfigLogMapper configLogMapper;

    private CacheInvalidationBus secondBus;
    private RedisMessageListenerContainer secondListener;
    private CachedOpenAppReader secondAppReader;
    private CachedTemplateReader secondTemplateReader;
    private CachedTenantConfigService secondTenantConfig;
    private CountDownLatch subscriptionReady;

    @BeforeEach
    void startSecondInstance() throws Exception {
        secondBus = new CacheInvalidationBus(redis, CHANNEL,
                "second-" + UUID.randomUUID(), true);
        secondAppReader = new CachedOpenAppReader(openAppRepository, secretCipher, secondBus,
                LONG_TTL_MS, FAR_REFRESH_MS, NEGATIVE_TTL_MS);
        secondTemplateReader = new CachedTemplateReader(templateRepository, secondBus,
                LONG_TTL_MS, FAR_REFRESH_MS, NEGATIVE_TTL_MS);
        secondTenantConfig = new CachedTenantConfigService(configMapper, configLogMapper,
                openAppRepository, event -> { }, secondBus, LONG_TTL_MS, FAR_REFRESH_MS);

        subscriptionReady = new CountDownLatch(1);
        secondListener = new RedisMessageListenerContainer();
        secondListener.setConnectionFactory(redisConnectionFactory);
        secondListener.addMessageListener(
                (message, pattern) -> {
                    secondBus.onMessage(new String(message.getBody(), StandardCharsets.UTF_8));
                    subscriptionReady.countDown();
                },
                new ChannelTopic(CHANNEL));
        secondListener.afterPropertiesSet();
        secondListener.start();
        awaitSubscription();
    }

    @AfterEach
    void stopSecondInstance() throws Exception {
        if (secondListener != null) {
            secondListener.stop();
            secondListener.destroy();
        }
    }

    // ------------------------------------------------------------------

    @Test
    @Timeout(90)
    @DisplayName("另一实例：密钥轮换后旧 SK 立即退出、新 SK 立即可用（TTL 远未到）")
    void openAppRotate_invalidatesSecondInstanceSecret() {
        String appId = newAppId();
        AppWithSecret created = openAppService.create(
                new CreateAppRequest(appId, "缓存失效-轮换", 0, 0, null));

        AppAuth before = secondAppReader.find(appId).orElseThrow();
        assertThat(before.secrets()).contains(created.secret());

        AppWithSecret rotated = openAppService.rotate(appId, Duration.ZERO);

        await(() -> {
            AppAuth current = secondAppReader.find(appId).orElseThrow();
            return current.secrets().contains(rotated.secret())
                    && !current.secrets().contains(created.secret());
        }, "第二实例应看到轮换后的新密钥，且零窗口轮换后旧密钥不再可用");
    }

    @Test
    @Timeout(90)
    @DisplayName("另一实例：接入方被停用后立即拒绝，而不是等 60s TTL")
    void appDisabled_rejectedOnSecondInstanceBeforeTtl() {
        String appId = newAppId();
        openAppService.create(new CreateAppRequest(appId, "缓存失效-停用", 0, 0, null));
        assertThat(secondAppReader.find(appId).orElseThrow().enabled()).isTrue();

        openAppService.setStatus(appId, OpenApp.STATUS_DISABLED);

        await(() -> !secondAppReader.find(appId).orElseThrow().enabled(),
                "第二实例应已看到停用状态");
    }

    @Test
    @Timeout(90)
    @DisplayName("另一实例：租户配置写入后立即读到新值（退款/开关/限流策略一致性）")
    void tenantConfigPut_visibleOnSecondInstance() {
        String appId = newAppId();
        openAppService.create(new CreateAppRequest(appId, "缓存失效-租户配置", 0, 0, null));
        String key = TenantConfigKey.RATE_LIMIT_QPS.key();
        assertThat(secondTenantConfig.getRaw(appId, key)).isNull();   // 先装载空配置

        tenantConfigService.put(appId, Map.of(key, "123"), "cache-invalidation-it");

        await(() -> "123".equals(secondTenantConfig.getRaw(appId, key)),
                "第二实例应已看到新租户配置");
    }

    @Test
    @Timeout(120)
    @DisplayName("另一实例：模板下线后立即读到 OFFLINE（不等 300s 模板 TTL）")
    void templateOffline_invalidatesSecondInstanceReader() {
        long templateId = createAndPublishTemplate("缓存失效-模板下线");
        CouponTemplate before = secondTemplateReader.get(templateId).orElseThrow();
        assertThat(before.getPublishStatus()).isEqualTo(PublishStatus.PUBLISHED);

        templateLifecycleService.offline(templateId);

        await(() -> secondTemplateReader.get(templateId)
                        .map(t -> t.getPublishStatus() == PublishStatus.OFFLINE)
                        .orElse(false),
                "第二实例应已看到模板下线");
    }

    @Test
    @Timeout(90)
    @DisplayName("pub/sub 消息丢失：版本键轮询仍能在下一轮恢复（模拟直接写库+只 INCR 不发布）")
    void lostPubSub_recoveredByVersionPoll() {
        String appId = newAppId();
        openAppService.create(new CreateAppRequest(appId, "缓存失效-版本兜底", 0, 0, null));
        assertThat(secondAppReader.find(appId).orElseThrow().enabled()).isTrue();

        secondBus.pollOnce();   // 建立版本基线，避免首次观测误判

        // 绕过服务层直接改库：不触发本地失效，也不广播——只推进版本键模拟"消息丢失"
        assertThat(openAppRepository.updateStatus(appId, OpenApp.STATUS_DISABLED)).isTrue();
        redis.opsForValue().increment("c:cache:ver:open-app");
        secondBus.pollOnce();

        await(() -> !secondAppReader.find(appId).orElseThrow().enabled(),
                "版本键落后时第二实例应全量失效并重新回源");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long createAndPublishTemplate(String name) {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateCreateRequest request = new TemplateCreateRequest(
                name, 9001L, 1, 2000L, null, 10_000L, 10L, 1,
                1, start, end, null, 0L, 0);
        long templateId = templateAppService.create(request).id();
        templateAppService.publish(templateId);
        return templateId;
    }

    private static String newAppId() {
        return "cache-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 等待第二实例真正订上频道：RedisMessageListenerContainer.start() 是异步建连，
     * 早于订阅完成的 PUBLISH 会丢。反复发 warmup，直到监听回调真的收到一条——
     * 这是"订阅已生效"的直接证据，不用固定 sleep 猜。
     */
    private void awaitSubscription() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && subscriptionReady.getCount() > 0) {
            redis.convertAndSend(CHANNEL, "warmup|dGVzdA|warmup-sender");
            subscriptionReady.await(100, TimeUnit.MILLISECONDS);
        }
        assertThat(subscriptionReady.getCount())
                .as("第二实例必须在 5s 内真正订阅上缓存失效频道")
                .isZero();
    }

    private static void await(BooleanSupplier condition, String description) {
        long deadline = System.currentTimeMillis() + 5_000;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待被中断：" + description);
            }
        }
        fail(description + (last == null ? "（5s 内未满足）" : "（最后异常：" + last + "）"));
    }
}
