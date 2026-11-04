package com.newcoupon.infrastructure.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
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

    /**
     * 控制台 SPA 入口（V6 2D）。静态文件本身由 Boot 默认的 classpath:/static/** 资源处理器
     * 服务（{@code /console/index.html}、{@code /console/assets/*} 天然可取）；这里只补
     * Boot 不做的两小步——实测（ConsoleTemplateListIntegrationTest ④）：
     * welcome page 机制只认**根目录** index.html，子目录 {@code GET /console/} 是 404。
     * <ul>
     *   <li>{@code /console/} → forward 到 {@code /console/index.html}（转发再走资源处理器，
     *       不重复实现静态服务）；</li>
     *   <li>{@code /console}（无斜杠）→ 302 到 {@code /console/}（Spring 6 起默认不做
     *       尾斜杠匹配，两类手输地址都能到）。</li>
     * </ul>
     * 认证语义：这两个视图控制器不在 /console/api/** 前缀下，不经过
     * LegacyAdminTokenAuthenticationFilter（静态资源匿名是设计，敏感数据全在
     * /console/api/** 与 /api/** 认证后面——见该过滤器的 isProtected 裁定）。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/console/").setViewName("forward:/console/index.html");
        registry.addRedirectViewController("/console", "/console/");
    }
}
