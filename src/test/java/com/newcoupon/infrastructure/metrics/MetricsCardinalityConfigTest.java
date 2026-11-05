package com.newcoupon.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指标基数防线单测（V6 5A）：高基数标签被拒、超标签数被拒、总量封顶（真实米数语义）。
 * 用 {@link MetricsCardinalityGuard} 的小上限构造器把闸门拧到可断言的量级——
 * 生产值（6/5000）语义相同，只是阈值不同。
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
    @DisplayName("单米标签数上限：7 个标签被拒；6 个放行（http.server.requests 在 Micrometer 1.13.6 下实际 6 标签）")
    void metersWithTooManyTagsAreDenied() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsCardinalityGuard guard = new MetricsCardinalityGuard(6, 1000);
        guard.attach(registry);   // 生产里由 MeterRegistryCustomizer 回调 attach
        registry.config().meterFilter(guard);

        // 6 个标签：Boot 内建 http.server.requests 的真实形态（method/uri/status/exception/outcome/error）
        counter(registry, "test_ok", List.of(
                Tag.of("method", "GET"), Tag.of("uri", "/api"), Tag.of("status", "200"),
                Tag.of("exception", ""), Tag.of("outcome", "SUCCESS"), Tag.of("error", "")));
        // 7 个标签：超出当前已知任何合法指标的形态，拒
        counter(registry, "test_too_many", List.of(
                Tag.of("a", "1"), Tag.of("b", "2"), Tag.of("c", "3"),
                Tag.of("d", "4"), Tag.of("e", "5"), Tag.of("f", "6"), Tag.of("g", "7")));

        assertThat(registry.find("test_ok").counters()).hasSize(1);
        assertThat(registry.find("test_too_many").counters()).isEmpty();
    }

    @Test
    @DisplayName("总米数封顶按真实米数：被单键闸拒掉的注册不占预算，事故后闸门可恢复（审查 minor 修正）")
    void totalMeterCountUsesRealRegisteredMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsCardinalityConfig config = new MetricsCardinalityConfig();
        // 上限 33：32 个穿透单键闸的垃圾米 + 1 个合法米刚好用满，第 2 个合法米被拒
        MetricsCardinalityGuard guard = new MetricsCardinalityGuard(6, 33);
        guard.attach(registry);
        registry.config().meterFilter(config.userIdTagValueCap());
        registry.config().meterFilter(guard);

        for (int i = 0; i < 100; i++) {
            counter(registry, "test_bomb", List.of(Tag.of("userId", "user-" + i)));
        }
        assertThat(registry.find("test_bomb").counters()).hasSize(32);

        // 被拒的 68 次注册**不占总量预算**（旧计数器版会把 100 次尝试全计入、闸门永久卡死）
        counter(registry, "test_legit_1", List.of());
        assertThat(registry.find("test_legit_1").counters())
                .as("拒新不烧预算：合法米仍可注册").hasSize(1);

        // 真实米数到 33 后，下一个新米才被总量闸拒
        counter(registry, "test_legit_2", List.of());
        assertThat(registry.find("test_legit_2").counters()).isEmpty();
    }

    private static void counter(SimpleMeterRegistry registry, String name, List<Tag> tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }
}
