package com.newcoupon.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指标基数防线单测（V6 5A）：高基数标签被拒、超标签数被拒、总米数封顶。
 * 用 {@link MetricsCardinalityGuard} 的小上限构造器把闸门拧到可断言的量级——
 * 生产值（5/5000）语义相同，只是阈值不同。
 */
class MetricsCardinalityConfigTest {

    @Test
    @DisplayName("危险标签键封顶：userId 出现第 33 个取值后被拒（内建 maximumAllowableTags）")
    void highCardinalityTagValuesAreDenied() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsCardinalityConfig config = new MetricsCardinalityConfig();
        registry.config().meterFilter(config.userIdTagValueCap());

        for (int i = 0; i < 100; i++) {
            counter(registry, "test_bomb", List.of(Tag.of("userId", "user-" + i)));
        }

        // 前 32 个取值放行（止血不追杀存量），其余全拒——爆炸半径钉死在常数
        assertThat(registry.find("test_bomb").counters())
                .as("userId 取值数必须被钉死在上限")
                .hasSize(MetricsCardinalityConfig.MAX_VALUES_PER_FORBIDDEN_KEY);
    }

    @Test
    @DisplayName("单米标签数上限：6 个标签的米被拒（合法指标最多 5 个，http.server.requests 恰为 5）")
    void metersWithTooManyTagsAreDenied() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MetricsCardinalityGuard(5, 1000));

        counter(registry, "test_ok", List.of(
                Tag.of("method", "GET"), Tag.of("uri", "/api"), Tag.of("status", "200"),
                Tag.of("exception", ""), Tag.of("outcome", "SUCCESS")));   // 5 个：放行
        counter(registry, "test_too_many", List.of(
                Tag.of("a", "1"), Tag.of("b", "2"), Tag.of("c", "3"),
                Tag.of("d", "4"), Tag.of("e", "5"), Tag.of("f", "6")));    // 6 个：拒

        assertThat(registry.find("test_ok").counters()).hasSize(1);
        assertThat(registry.find("test_too_many").counters()).isEmpty();
    }

    @Test
    @DisplayName("总米数封顶：超过上限后新米全拒，既有米不受影响")
    void totalMeterCountIsCapped() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MetricsCardinalityGuard(5, 10));

        for (int i = 0; i < 30; i++) {
            counter(registry, "test_cap_" + i, List.of());
        }

        assertThat(registry.getMeters()).as("总量钉死在上限 10").hasSize(10);
        // 第 1 个米在封顶前注册，仍可正常计数——闸门止血，不禁用存量
        double before = registry.get("test_cap_0").counter().count();
        registry.get("test_cap_0").counter().increment();
        assertThat(registry.get("test_cap_0").counter().count()).isEqualTo(before + 1);
    }

    private static void counter(SimpleMeterRegistry registry, String name, List<Tag> tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }
}
