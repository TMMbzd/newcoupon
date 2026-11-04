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
 * 操作审计切面（第 1 环 1-1 项；V6 2C 升级 actor 采集）。
 *
 * <p>拦截带 {@link AuditLog @AuditLog} 注解的方法，自动采集六要素并提交审计服务。
 *
 * <p>六要素的采集来源：
 * <ul>
 *   <li>谁（appId）：RequestContext（签名认证过滤器写入）
 *   <li>谁（userId）：从方法入参中找第一个 Long 类型参数——见下"操作对象"说明
 *   <li>何时：操作时间（AuditService 写入时取当前时间）
 *   <li>何处（IP）：HttpServletRequest（通过 RequestContextHolder 获取；非 HTTP 调用时为 null）
 *   <li>何事（action/resourceType）：@AuditLog 注解的属性
 *   <li>前后值快照：入参序列化为 before、返回值序列化为 after
 *   <li>链路 ID（traceId）：RequestContext
 * </ul>
 *
 * <p><b>actor 采集（V6 2C，替代"入参第一个 Long 猜 userId 当调用者"的旧约定）</b>：
 * 调用者身份由 {@link AuditActorResolver} 按"管理面会话 → 开放面应用 → 系统"解析，
 * 不再从业务参数里猜。入参第一个 Long 的约定**保留**，但语义收窄为"操作对象"维度
 * （userId 列回答"动的是哪个业务用户的东西"，actor 三列回答"谁干的"）——
 * 核销动的是某张用户券，两个问题都要能答，互不替代。
 *
 * <p>设计约束：审计提交是异步的（不阻塞业务方法）；业务方法抛异常时也记录审计（after 为异常信息）。
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;
    private final AuditActorResolver actorResolver;

    public AuditAspect(AuditService auditService, AuditActorResolver actorResolver) {
        this.auditService = auditService;
        this.actorResolver = actorResolver;
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

    /** 组装六要素 + actor 并提交审计服务（fire-and-forget） */
    private void submit(ProceedingJoinPoint pjp, AuditLog annotation,
                        String before, String after, int resultCode) {
        try {
            RequestContext ctx = RequestContext.current();
            String appId = ctx != null ? ctx.appId() : null;
            String traceId = ctx != null ? ctx.traceId() : null;
            Long userId = extractUserId(pjp.getArgs());
            AuditActorResolver.Actor actor = actorResolver.resolve();

            auditService.record(AuditService.AuditContext.of(
                    appId, userId, traceId,
                    annotation.action(), annotation.resourceType(), null,
                    before, after,
                    extractIp(), resultCode,
                    actor.type(), actor.actorId(), actor.sessionId()));
        } catch (Exception e) {
            // 审计组装失败不影响业务——只记日志
            log.error("AUDIT ASSEMBLE FAILED: action={}", annotation.action(), e);
        }
    }

    /**
     * 从方法入参中提取业务用户 ID（约定：第一个 Long 类型参数）。
     * 这是"操作对象"维度——它不是 actor：调用者是谁由 {@link AuditActorResolver} 回答。
     */
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
