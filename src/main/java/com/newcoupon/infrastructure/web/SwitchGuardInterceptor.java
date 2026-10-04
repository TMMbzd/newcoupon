package com.newcoupon.infrastructure.web;

import com.newcoupon.application.service.SwitchService;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.common.context.SwitchGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 开关拦截器（S1 第 9 步）：执行 {@link SwitchGuard} 注解声明的开关检查。
 *
 * <p>关闭时抛 {@code BizException(DEGRADED)} → 由全局异常处理器转成 **5001（可重试）**——
 * 开关多半是临时的，告诉调用方"稍后重试"比"这个功能没了"更贴近事实。
 *
 * <p>注册在 {@code /**}（内部面与开放面都受控）：开关是运维手段，
 * 不该出现"开放面关了、内部面还能打"的漏洞——出事的往往是内部调用。
 */
@Component
public class SwitchGuardInterceptor implements HandlerInterceptor {

    private final SwitchService switchService;

    public SwitchGuardInterceptor(SwitchService switchService) {
        this.switchService = switchService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            SwitchGuard guard = method.getMethodAnnotation(SwitchGuard.class);
            if (guard != null) {
                // 关闭 → 抛 DEGRADED，请求不进入业务。
                //
                // **appId 从请求上下文取**（S2 第 10 步）：开放面是签名认证过的接入方，
                // 内部面（/api/**）与定时任务没有这个身份，取到 null —— 于是租户层对它们不生效，
                // 只用全站层。这条不对称是有意的（见 SwitchService.isOn 的注释）。
                //
                // Filter 先于 Interceptor 执行，所以这里读到的上下文一定是本请求的
                // （OpenApiAuthFilter 在认证通过后 set，并在 finally 里 clear）。
                switchService.requireOn(guard.value(), RequestContext.current().appId());
            }
        }
        return true;
    }
}
