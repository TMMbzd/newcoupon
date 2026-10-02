package com.newcoupon.infrastructure.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 接入层 MVC 装配（S1）。
 *
 * <p>拦截器**只在开放面上注册**（{@code /open/**}）：内部面（{@code /api/**}）没有
 * 接入方上下文，挂上去也只是空转，还会让人误以为内部面也在做资源归属统计。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final OpenApiResourceInterceptor openApiResourceInterceptor;
    private final SwitchGuardInterceptor switchGuardInterceptor;

    public WebMvcConfig(OpenApiResourceInterceptor openApiResourceInterceptor,
                        SwitchGuardInterceptor switchGuardInterceptor) {
        this.openApiResourceInterceptor = openApiResourceInterceptor;
        this.switchGuardInterceptor = switchGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 开关守卫挂全路径：开关是运维手段，不该出现"开放面关了、内部面还能打"的漏洞
        registry.addInterceptor(switchGuardInterceptor).addPathPatterns("/**");
        registry.addInterceptor(openApiResourceInterceptor).addPathPatterns("/open/**");
    }
}
