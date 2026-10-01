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

    public WebMvcConfig(OpenApiResourceInterceptor openApiResourceInterceptor) {
        this.openApiResourceInterceptor = openApiResourceInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(openApiResourceInterceptor)
                .addPathPatterns("/open/**");
    }
}
