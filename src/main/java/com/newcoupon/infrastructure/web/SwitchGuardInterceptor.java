package com.newcoupon.infrastructure.web;

import com.newcoupon.application.service.SwitchService;
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
                switchService.requireOn(guard.value());   // 关闭 → 抛 DEGRADED，请求不进入业务
            }
        }
        return true;
    }
}
