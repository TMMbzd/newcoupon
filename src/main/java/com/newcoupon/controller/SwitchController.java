package com.newcoupon.controller;

import com.newcoupon.application.service.SwitchService;
import com.newcoupon.application.service.SwitchService.SwitchView;
import com.newcoupon.common.Result;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开关读取接口（S1 第 9 步）——运维的"我能关什么"清单。
 *
 * <p>只读、不设防（与 {@code GET /api/templates} 一致）：开关清单不含敏感信息，
 * 而"能读到当前状态"本身就是排障的第一步（"我明明关了怎么还生效" →
 * 一看 {@code configured:false} 就知道配错了地方）。
 *
 * <p><b>接口只读不写</b>：改开关走配置（或 S2 的开关中心），不在这里开一个写口——
 * 一个能被 HTTP 改的开关，等于把"关掉核心功能"的能力暴露在网络上。
 */
@RestController
@RequestMapping("/api/switches")
public class SwitchController {

    private final SwitchService switchService;
    private final com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers circuitBreakers;

    public SwitchController(SwitchService switchService,
                            com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers circuitBreakers) {
        this.switchService = switchService;
        this.circuitBreakers = circuitBreakers;
    }

    /**
     * 熔断器当前状态（S2 第 10 步）——**新增的只读观测口**。
     *
     * <p>为什么需要它：第 10 步的演练脚本要判定"注入故障 → 熔断打开 → 恢复 → 闭合"，
     * 但本项目没有 resilience4j-micrometer（离线拉不到新依赖），
     * {@code /actuator/circuitbreakers} 与 Prometheus 的 breaker 指标都不存在。
     * 没有这个口，脚本只能靠"响应码 + 时延"猜——而时延是不可控量，负载下必然 flaky。
     *
     * <p>只读、不设防（与开关清单一致）：熔断状态不含敏感信息，
     * 而"能读到当前状态"本身就是排障与演练的第一步。
     */
    @GetMapping("/breakers")
    public Result<List<BreakerView>> breakers() {
        // **先确保依赖级熔断器存在**：Resilience4j 的 registry 是懒创建的，
        // 没被调用过的依赖根本不在 registry 里——不触发一次，运维打开这个端点会看到**空清单**，
        // 然后以为"接口坏了"或"没有熔断器"（实测：第一版就是这样）。
        // 显式列出来也符合本项目的思路：清单本身就是给运维的说明书。
        circuitBreakers.redis();
        circuitBreakers.mysql();

        return Result.success(circuitBreakers.registry().getAllCircuitBreakers().stream()
                .map(cb -> new BreakerView(
                        cb.getName(),
                        cb.getState().name(),
                        cb.getMetrics().getNumberOfSuccessfulCalls(),
                        cb.getMetrics().getNumberOfFailedCalls(),
                        cb.getMetrics().getNumberOfNotPermittedCalls()))
                .sorted(java.util.Comparator.comparing(BreakerView::name))
                .toList());
    }

    /**
     * @param name          熔断器名（依赖级为 {@code redis}/{@code mysql}；租户级为 {@code 依赖:appId}）
     * @param state         {@code CLOSED} / {@code OPEN} / {@code HALF_OPEN}
     * @param notPermitted  被快速失败挡下的调用数——**它开始涨就说明熔断真的在起作用**
     */
    public record BreakerView(String name, String state, long successful, long failed,
                              long notPermitted) {
    }

    /**
     * 开关清单。带 {@code appId} 时按该租户的三层取值解析，并给出**来源**
     * （{@code TENANT} / {@code GLOBAL} / {@code DEFAULT}）。
     *
     * <p>不带 {@code appId} 时保持原样（只看全站层）——兼容既有调用方，
     * 也对应"内部面没有接入方身份"这一语义。
     */
    @GetMapping
    public Result<List<SwitchView>> list(
            @RequestParam(value = "appId", required = false) String appId) {
        return Result.success(switchService.all(appId));
    }
}
