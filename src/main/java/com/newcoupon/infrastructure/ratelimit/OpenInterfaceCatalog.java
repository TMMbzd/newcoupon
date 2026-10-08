package com.newcoupon.infrastructure.ratelimit;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 开放面**路径模式目录**（迁移第 8 步，2026-10-08）。
 *
 * <p><b>它要解决什么</b>：接口维度的规则必须按"适配器会 enter 的那个资源名"来配，
 * 而那个名字是 Spring MVC 在匹配 handler 时决定的（{@code BEST_MATCHING_PATTERN_ATTRIBUTE}）。
 * 与其在代码里手抄一份模式清单（抄错一个字符 = 规则静默不命中），
 * 不如**启动期直接从 handler mapping 里扫出来**——同一个来源，不可能漂移。
 *
 * <p><b>为什么在 {@code afterSingletonsInstantiated} 扫</b>：要等
 * {@code RequestMappingHandlerMapping} 完成 handler 探测（它在 {@code afterPropertiesSet} 里做），
 * 这个回调的点正好在"所有单例创建完、还没开始服务请求"之间。
 * 扫完立刻发事件让规则源重推一次（见 {@link Event}）——否则首次推送（发生在
 * {@code SentinelRuleConfig} 的构造器里）拿到的是一份空目录，接口维度要等下一个刷新周期才生效。
 *
 * <p><b>fail fast</b>：开关开着却一个模式都没扫到，说明扫描时机或路径前缀出了问题。
 * 这种"开了但没生效"必须当场炸，不能等运维去猜（项目的先例：{@code SwitchPlanStartupCheck}）。
 *
 * <p><b>为什么只扫开放面</b>：接口维度是**开放面**的平台保护（内部面代表平台自己，
 * 不设平台级配额）。所以只留 {@code /open/} 前缀的模式——
 * 注意本项目同一个 Controller 同时挂了 {@code /api/**} 与 {@code /open/v1/**} 两套路径，
 * 所以一条业务方法会扫出两个模式，我们只要开放面那个。
 */
@Component
public class OpenInterfaceCatalog implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(OpenInterfaceCatalog.class);

    /** 与适配器的资源名同源：Spring MVC 的路径模式串 */
    private static final String OPEN_PREFIX = "/open/";

    private final RequestMappingHandlerMapping handlerMapping;
    private final ApplicationEventPublisher publisher;
    private final boolean enabled;
    private final int qps;
    private final int warmupSeconds;
    private final int queueMs;

    /** 扫出来的模式（顺序稳定，便于日志比对） */
    private volatile Set<String> patterns = Set.of();

    public OpenInterfaceCatalog(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            ApplicationEventPublisher publisher,
            @Value("${coupon.ratelimit.resource.enabled:false}") boolean enabled,
            @Value("${coupon.ratelimit.resource.qps:0}") int qps,
            @Value("${coupon.ratelimit.resource.warmup-seconds:0}") int warmupSeconds,
            @Value("${coupon.ratelimit.resource.queue-ms:0}") int queueMs) {
        this.handlerMapping = handlerMapping;
        this.publisher = publisher;
        this.enabled = enabled;
        this.qps = qps;
        this.warmupSeconds = warmupSeconds;
        this.queueMs = queueMs;
    }

    /**
     * 把"目录 + 全站配置"翻译成接口维度的规则。
     *
     * <p>配置与目录都收在这个类里，规则生成只在这里发生——调用方（规则源）不需要知道
     * 接口维度的开关与阈值从哪来，也就不会出现"两个地方各判一次开关"的经典分叉。
     */
    public InterfaceFlowRuleMapper.Mapping toRules() {
        return InterfaceFlowRuleMapper.map(patterns, enabled, qps, warmupSeconds, queueMs);
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : entry.getKey().getPatternValues()) {
                if (pattern.startsWith(OPEN_PREFIX)) {
                    found.add(pattern);
                }
            }
        }
        this.patterns = Set.copyOf(found);

        if (enabled && found.isEmpty()) {
            // 开了却扫不到 → 接口维度会**静默不存在**。宁可启动失败。
            throw new IllegalStateException(
                    "coupon.ratelimit.resource.enabled=true 但启动期没有扫到任何 " + OPEN_PREFIX
                            + " 开头的路径模式：接口维度不会生效（检查扫描时机与 @RequestMapping 的路径）");
        }
        log.info("开放面路径模式目录已建立：{} 条{}", found.size(),
                enabled ? "（接口维度已启用，将按它生成规则）" : "（接口维度未启用）");

        // 让规则源立刻重推一次：首次推送发生在上下文启动早期，那时这份目录还是空的
        publisher.publishEvent(new Event(found.size()));
    }

    public Set<String> patterns() {
        return patterns;
    }

    /** 目录就绪事件——规则源收到后主动推一次（与"写后推"同一个套路） */
    public record Event(int patternCount) {
    }
}
