package com.newcoupon.infrastructure.ratelimit;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.SentinelWebInterceptor;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcConfig;
import com.newcoupon.common.context.RequestContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sentinel 的 Web 接线（2026-10-08，第 0 步可行性验证阶段）。
 *
 * <p><b>为什么必须手写这一段</b>：官方**没有** Spring Boot starter，也**没有自动配置**
 * （Maven Central 上那个 {@code sentinel-spring-boot-starter} 是 2018 年的 0.1.0，
 * 不能用；1.8 分支里也没有 {@code spring.factories}）。所以拦截器要自己注册。
 *
 * <p><b>用 v6x 适配器而不是 sentinel-web-servlet</b>：后者仍是 {@code javax.servlet}
 * （3.1.0 provided），与 Spring Boot 3.3.5 的 jakarta 命名空间不兼容。
 * {@code sentinel-spring-webmvc-v6x-adapter} 是官方自 1.8.8 起为 Boot 3 提供的适配。
 *
 * <p><b>origin（租户身份）怎么来</b>：{@code setOriginParser} 把请求映射成"调用方"，
 * 它是 {@code FlowRule.limitApp} 能生效的唯一开关——也就是"按租户限流"的挂载点。
 * 这里取 {@link RequestContext#appId()}（认证过滤器写入的、**验签通过**的接入方标识），
 * 而不是请求头里声明的那个——这与"审计只信验签后的身份"是同一条纪律。
 *
 * <p><b>拦截器注册在 {@code /**}</b>：内部面与开放面都要被统计（与开关拦截器的口径一致）。
 * 资源名由适配器按"匹配到的路径模式"决定，所以内部面与开放面各自成资源，
 * 不需要我们手工命名。
 */
@Configuration
public class SentinelWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注意 API 形状：setOriginParser / setBlockExceptionHandler 在 **config** 上，
        // 不在拦截器上（拦截器只有 getter）。所以是"先配 config，再用它构造拦截器"。
        // 这是实测出来的——照直觉写 `new SentinelWebInterceptor().setOriginParser(...)` 编译不过。
        SentinelWebMvcConfig config = new SentinelWebMvcConfig();
        // 未验签的请求 appId 为空 → 适配器回退成 EMPTY_ORIGIN，
        // 于是 limitApp=appId 的规则匹配不上、只有 limitApp="default" 的规则对它生效。
        // 这是**期望行为**：认不出身份的请求，不该享受"某个租户的配额"。
        config.setOriginParser(request -> RequestContext.current().appId());

        registry.addInterceptor(new SentinelWebInterceptor(config)).addPathPatterns("/**");
    }
}
