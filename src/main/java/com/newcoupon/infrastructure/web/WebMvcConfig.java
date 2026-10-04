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
        // 开关守卫挂全路径：**全站级**开关对开放面与内部面一视同仁——
        // 不该出现"开放面关了、内部面还能打"的漏洞（出事的往往是内部调用）。
        //
        // ⚠️ 但**租户级**开关只对开放面生效（S2 第 10 步起）：只有开放面带得到接入方身份
        // （RequestContext.appId 由 OpenApiAuthFilter 认证后写入）。内部面与定时任务取到 null，
        // 于是只用全站层——这是**有意的语义不对称**，不是漏配。
        // 运维若以为"给某租户关了开关"对内部面也生效，会在故障中得出错误结论。
        registry.addInterceptor(switchGuardInterceptor).addPathPatterns("/**");
        registry.addInterceptor(openApiResourceInterceptor).addPathPatterns("/open/**");
    }
}
