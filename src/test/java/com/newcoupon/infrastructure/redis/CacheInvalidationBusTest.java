package com.newcoupon.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 缓存失效总线的消息契约与失败立场（不启动 Spring/Redis）：
 *
 * <ul>
 *   <li>发布：先 INCR 版本键再 PUBLISH——PUBLISH 丢失时轮询仍能发现版本差；</li>
 *   <li>订阅：忽略自己 instanceId 的消息；只派发给已注册 namespace；坏消息只忽略；</li>
 *   <li>轮询：首次建基线不误失效；版本落后触发一次全量失效；Redis 故障只跳过；</li>
 *   <li>失败立场：发布/读取异常绝不向业务抛出。</li>
 * </ul>
 */
class CacheInvalidationBusTest {

    private static final String CHANNEL = "c:cache:invalidate";
    private static final String TEMPLATE_VERSION_KEY = "c:cache:ver:template";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private CacheInvalidationBus bus;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        bus = new CacheInvalidationBus(redis, CHANNEL, "instance-A", true);
    }

    // ------------------------------------------------------------------
    // 发布
    // ------------------------------------------------------------------

    @Test
    @DisplayName("发布：先 INCR 版本键，再 PUBLISH namespace|base64url(key)|instanceId")
    void publish_incrementsVersionThenSendsPayload() {
        when(valueOps.increment(TEMPLATE_VERSION_KEY)).thenReturn(1L);

        bus.invalidate("template", "42");

        InOrder order = inOrder(valueOps, redis);
        order.verify(valueOps).increment(TEMPLATE_VERSION_KEY);
        order.verify(redis).convertAndSend(CHANNEL, "template|NDI|instance-A");
    }

    @Test
    @DisplayName("Redis 发布异常只 WARN，不向业务抛出")
    void publishFailure_doesNotThrowAndDoesNotFailCaller() {
        when(valueOps.increment(TEMPLATE_VERSION_KEY)).thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> bus.invalidate("template", "42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("版本键写成功但 PUBLISH 失败：同样不抛异常（轮询兜底）")
    void publishMessageFailure_doesNotThrow() {
        when(valueOps.increment(TEMPLATE_VERSION_KEY)).thenReturn(1L);
        when(redis.convertAndSend(anyString(), anyString())).thenThrow(new IllegalStateException("down"));

        assertThatCode(() -> bus.invalidate("template", "42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("关闭开关：不触碰 Redis（退回纯本地失效 + TTL）")
    void disabledBus_neverTouchesRedis() {
        CacheInvalidationBus disabled = new CacheInvalidationBus(redis, CHANNEL, "instance-A", false);

        disabled.invalidate("template", "42");
        disabled.onMessage("template|NDI|instance-B");
        disabled.pollOnce();

        verifyNoInteractions(valueOps);
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // 订阅
    // ------------------------------------------------------------------

    @Test
    @DisplayName("订阅：忽略自己发出的消息，避免重复回源")
    void onMessage_ignoresSelf() {
        AtomicReference<String> received = new AtomicReference<>();
        bus.register("template", received::set, () -> { });

        bus.onMessage("template|NDI|instance-A");

        assertThat(received.get()).isNull();
    }

    @Test
    @DisplayName("订阅：其他实例的消息按 namespace 派发并解出原始 key")
    void onMessage_dispatchesOtherInstance() {
        AtomicReference<String> received = new AtomicReference<>();
        bus.register("template", received::set, () -> { });

        bus.onMessage("template|NDI|instance-B");

        assertThat(received).hasValue("42");
    }

    @Test
    @DisplayName("订阅：未知 namespace / 坏格式 / 坏编码都只忽略，不影响后续消息")
    void onMessage_ignoresUnknownAndMalformed() {
        AtomicInteger templateHits = new AtomicInteger();
        bus.register("template", key -> templateHits.incrementAndGet(), () -> { });

        assertThatCode(() -> bus.onMessage("unknown|NDI|instance-B")).doesNotThrowAnyException();
        assertThatCode(() -> bus.onMessage("not-a-valid-message")).doesNotThrowAnyException();
        assertThatCode(() -> bus.onMessage("template|%%%|instance-B")).doesNotThrowAnyException();

        assertThat(templateHits).hasValue(0);
    }

    // ------------------------------------------------------------------
    // 版本键轮询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("轮询：首次只建基线，不误触发全量失效")
    void poll_firstObservationOnlyEstablishesBaseline() {
        AtomicInteger invalidated = new AtomicInteger();
        bus.register("template", key -> { }, invalidated::incrementAndGet);
        when(valueOps.get(TEMPLATE_VERSION_KEY)).thenReturn("7");

        bus.pollOnce();

        assertThat(invalidated).hasValue(0);
    }

    @Test
    @DisplayName("轮询：版本落后且没收到广播 → 触发一次全量失效")
    void poll_versionGap_invalidatesAll() {
        AtomicInteger invalidated = new AtomicInteger();
        bus.register("template", key -> { }, invalidated::incrementAndGet);
        when(valueOps.get(TEMPLATE_VERSION_KEY)).thenReturn("7", "8");

        bus.pollOnce();   // 基线 7
        bus.pollOnce();   // 8 > 7 → 全量失效

        assertThat(invalidated).hasValue(1);
    }

    @Test
    @DisplayName("轮询：版本未变化不失效")
    void poll_noChange_noInvalidation() {
        AtomicInteger invalidated = new AtomicInteger();
        bus.register("template", key -> { }, invalidated::incrementAndGet);
        when(valueOps.get(TEMPLATE_VERSION_KEY)).thenReturn("7", "7");

        bus.pollOnce();
        bus.pollOnce();

        assertThat(invalidated).hasValue(0);
    }

    @Test
    @DisplayName("轮询：版本键缺失后重新出现，也按版本变化处理")
    void poll_missingThenCreated_invalidatesAll() {
        AtomicInteger invalidated = new AtomicInteger();
        bus.register("template", key -> { }, invalidated::incrementAndGet);
        when(valueOps.get(TEMPLATE_VERSION_KEY)).thenReturn(null, "1");

        bus.pollOnce();   // 缺失=基线 0
        bus.pollOnce();   // 1 > 0 → 全量失效

        assertThat(invalidated).hasValue(1);
    }

    @Test
    @DisplayName("轮询：Redis 读取异常只跳过本轮，不抛异常")
    void poll_readFailure_doesNotThrow() {
        bus.register("template", key -> { }, () -> { });
        when(valueOps.get(TEMPLATE_VERSION_KEY)).thenThrow(new IllegalStateException("down"));

        assertThatCode(bus::pollOnce).doesNotThrowAnyException();
    }
}
