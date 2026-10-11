package com.newcoupon.infrastructure.audit;

import com.newcoupon.application.service.AuditService;
import com.newcoupon.common.AuditLog;
import com.newcoupon.common.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作审计切面（第 1 环 1-1 项）。
 *
 * <p>拦截带 {@link AuditLog @AuditLog} 注解的方法，自动采集六要素并提交审计服务。
 *
 * <p>六要素的采集来源：
 * <ul>
 *   <li>谁（appId）：RequestContext（签名认证过滤器写入）</li>
 *   <li>谁（userId）：从方法入参中找第一个 Long 类型参数（约定：试算/用券方法的第一个 Long 通常是用户 ID）</li>
 *   <li>何时：操作时间（AuditService 写入时取当前时间）</li>
 *   <li>何处（IP）：HttpServletRequest（通过 RequestContextHolder 获取；非 HTTP 调用时为 null）</li>
 *   <li>何事（action/resourceType）：@AuditLog 注解的属性</li>
 *   <li>前后值快照：入参序列化为 before、返回值序列化为 after</li>
 *   <li>链路 ID（traceId）：RequestContext</li>
 * </ul>
 *
 * <p>设计约束：审计提交是异步的（不阻塞业务方法）；业务方法抛异常时也记录审计（after 为异常信息）。
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        // 前值快照：序列化方法入参
        String before = AuditService.serialize(pjp.getArgs());

        try {
            Object result = pjp.proceed();

            // 后值快照：序列化返回值
            String after = AuditService.serialize(result);
            submit(pjp, auditLog, before, after, 0);
            return result;
        } catch (Throwable t) {
            // 异常也记录审计（after 为异常信息）
            submit(pjp, auditLog, before, "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage(), 500);
            throw t;
        }
    }

    /** 组装六要素并提交审计服务（fire-and-forget） */
    private void submit(ProceedingJoinPoint pjp, AuditLog annotation,
                        String before, String after, int resultCode) {
        try {
            RequestContext ctx = RequestContext.current();
            String appId = ctx != null ? ctx.appId() : null;
            String traceId = ctx != null ? ctx.traceId() : null;
            Long userId = extractUserId(pjp.getArgs());

            auditService.record(new AuditService.AuditContext(
                    appId, userId, traceId,
                    annotation.action(), annotation.resourceType(), null,
                    before, after,
                    extractIp(), resultCode));
        } catch (Exception e) {
            // 审计组装失败不影响业务——只记日志
            log.error("AUDIT ASSEMBLE FAILED: action={}", annotation.action(), e);
        }
    }

    /** 从方法入参中提取用户 ID（约定：第一个 Long 类型参数） */
    private Long extractUserId(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Long l) {
                return l;
            }
        }
        return null;
    }

    /** 从 HTTP 请求上下文提取来源 IP（非 HTTP 调用时返回 null） */
    private String extractIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                return request.getRemoteAddr();
            }
        } catch (Exception ignored) {
            // 非 HTTP 上下文（如后台任务）没有 IP
        }
        return null;
    }
}
