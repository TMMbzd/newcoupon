package com.newcoupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.newcoupon.application.service.SwitchService;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 熔断观测接口（S2 第 10 步）。
 *
 * <p>为什么这个"只是读状态"的接口值得一组用例：<b>它是演练脚本唯一的可靠判据</b>。
 * 本项目没有 resilience4j-micrometer，{@code /actuator/circuitbreakers} 与 Prometheus
 * 的 breaker 指标都不存在；没有这个口，脚本只能靠响应码 + 时延猜，
 * 而时延是不可控量（测试铁律③），负载一高就 flaky。
 *
 * <p>用例的设计要点：**断言状态随实际状态变化**（先 CLOSED，打开后 OPEN），
 * 而不是只断言"字段存在"——后者在实现把状态写死时照样绿。
 */
class SwitchControllerTest {

    private final MockEnvironment env = new MockEnvironment();

    private DependencyCircuitBreakers newBreakers() {
        return new DependencyCircuitBreakers(50f, 1000L, 30L, 10, 5, 3,
                mock(TenantConfigService.class));
    }

    private SwitchController newController(DependencyCircuitBreakers breakers) {
        return new SwitchController(
                new SwitchService(env, mock(TenantConfigService.class)), breakers);
    }

    private static SwitchController.BreakerView find(java.util.List<SwitchController.BreakerView> views,
                                                     String name) {
        return views.stream().filter(v -> v.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("清单里没有熔断器 " + name + "：" + views));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("初始状态可读：依赖级 redis / mysql 都以 CLOSED 出现在清单里")
    void reportsDependencyBreakersAsClosed() {
        var views = newController(newBreakers()).breakers().getData();

        assertThat(find(views, "redis").state()).isEqualTo("CLOSED");
        assertThat(find(views, "mysql").state()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("熔断打开后读到 OPEN，且快速失败计数会涨（脚本判定「熔断真的在起作用」的依据）")
    void reportsOpenStateAndFastFailures() {
        var breakers = newBreakers();
        var controller = newController(breakers);
        assertThat(find(controller.breakers().getData(), "redis").state())
                .as("前置条件：一开始是闭合的").isEqualTo("CLOSED");

        breakers.redis().transitionToOpenState();
        // 打开状态下再调用会被"快速失败"拦下——这正是脚本要观察的现象
        for (int i = 0; i < 3; i++) {
            var permitted = breakers.redis().tryAcquirePermission();
            if (!permitted) {
                breakers.redis().releasePermission();
            }
        }

        var view = find(controller.breakers().getData(), "redis");
        assertThat(view.state())
                .as("把状态写死成 CLOSED 会让这条失败——这正是它要守的").isEqualTo("OPEN");
        assertThat(view.notPermitted())
                .as("被拦下的次数——它开始涨就说明熔断真的在起作用").isPositive();
    }

    @Test
    @DisplayName("失败计数可读：喂真实故障形态后 failed 增长")
    void reportsFailedCount() {
        var breakers = newBreakers();
        var controller = newController(breakers);

        for (int i = 0; i < 3; i++) {
            breakers.redis().onError(0, TimeUnit.NANOSECONDS, new SocketTimeoutException("模拟超时"));
        }

        assertThat(find(controller.breakers().getData(), "redis").failed())
                .as("排障时要能回答「失败了几次、离阈值还有多远」").isEqualTo(3);
    }

    @Test
    @DisplayName("租户级熔断器也在清单里（名字带 appId，能定位到具体是谁）")
    void includesTenantBreakers() {
        var breakers = newBreakers();
        breakers.forTenant("redis", "app-x");

        var views = newController(breakers).breakers().getData();

        assertThat(find(views, "redis:app-x").state()).isEqualTo("CLOSED");
    }
}
