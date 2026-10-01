package com.newcoupon.infrastructure.web;

import com.newcoupon.common.context.OpenResource;
import com.newcoupon.common.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 资源名拦截器（S1 治理插槽②）：把 {@link OpenResource} 注解声明的资源名写进请求上下文。
 *
 * <p><b>为什么用拦截器而不是过滤器</b>：注解在 Controller 的<b>方法</b>上，
 * 过滤器执行时还没有 handler 的方法信息（HandlerMapping 在过滤器链之后才跑）。
 * 而拦截器拿得到 {@link HandlerMethod}——它就是为了"知道自己在处理哪个方法"而存在的。
 *
 * <p><b>为什么只做 preHandle、不在这里回写响应头</b>：Spring MVC 的行为是——
 * handler 抛异常时 <b>postHandle 不会被调用</b>（异常走 HandlerExceptionResolver 直接出响应）。
 * 而"业务失败"恰恰是最需要看资源名的场景（限流按资源算，接入方要确认自己算在哪个资源上）。
 * 所以响应头与访问日志统一放在 {@code OpenApiAuthFilter} 的 finally 里——那里**无论成功失败都会执行**。
 */
@Component
public class OpenApiResourceInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            OpenResource annotation = method.getMethodAnnotation(OpenResource.class);
            if (annotation != null) {
                // 注解优先于过滤器的路径推导（注解是"契约"，路径是"兜底"）
                RequestContext.current().resource(annotation.value());
            }
        }
        return true;
    }
}
