package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcoupon.infrastructure.redis.SoldOutHint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 售罄提示键组件（方案 A）：三方法行为 + Redis 故障 fail-open。 */
class SoldOutHintTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SoldOutHint hint;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        hint = new SoldOutHint(redis);
    }

    @Test
    @DisplayName("mark：SET c:so:{tpl} \"1\" 带 10 秒过期")
    void markWritesKeyWithTtl() {
        hint.mark(501L);
        Mockito.verify(valueOps).set("c:so:{501}", "1", java.time.Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("isSet：EXISTS 判定，键在即真")
    void isSetReadsExistence() {
        when(redis.hasKey("c:so:{501}")).thenReturn(true);
        assertThat(hint.isSet(501L)).isTrue();
        when(redis.hasKey("c:so:{501}")).thenReturn(false);
        assertThat(hint.isSet(501L)).isFalse();
    }

    @Test
    @DisplayName("clear：DELETE 删除键")
    void clearDeletesKey() {
        hint.clear(501L);
        Mockito.verify(redis).delete("c:so:{501}");
    }

    @Test
    @DisplayName("Redis 故障：读按不存在（fail-open），写/删只吞异常")
    void redisFailureFailsOpen() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThat(hint.isSet(501L)).isFalse();   // 走完整路径，提示不阻断

        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), Mockito.any(java.time.Duration.class));
        hint.mark(501L);                            // 不抛
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(redis).delete(anyString());
        hint.clear(501L);                           // 不抛
    }
}
