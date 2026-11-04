package com.newcoupon.infrastructure.audit;

import com.newcoupon.application.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 管理面写动作审计（V6 2C 审查 M3）：回答"是哪个管理员干了这件事"。
 *
 * <p><b>开放面（actor=TENANT）静默跳过，其余都记</b>——同一段业务代码大多同时挂在
 * /api（管理面）与 /open（开放面）两个前缀下：开放面调用已有 open_api_audit
 * 专表全量覆盖（appId/资源/结果码/耗时），这里再写就是同一动作两份账；
 * 管理面调用此前**零留痕**（租户创建/启停/密钥轮换/模板治理——rotate 连专表都没有，
 * open_app_lifecycle_event 只记状态机迁移，open_api_audit 只见"租户自己"）。
 * 旧管理令牌的 principal 是共享机器凭据（resolver 判 SYSTEM）——动作照记
 * （"管理面发生了什么"比"具体是哪个人"更不可让步），个人身份靠控制台会话面补齐。
 *
 * <p>使用约定（与 AdminSessionService 的会话审计同一模式）：业务成功后调
 * {@link #record}，action 用域前缀（APP_/TEMPLATE_…），resourceId 放被操作的
 * 业务标识（查询端点按 resourceId 过滤就靠它）；失败路径不留痕——被 @PreAuthorize
 * 拒掉的请求根本到不了服务层，参数/状态失败的排查走 ERROR 日志与 traceId。
 */
@Component
public class AdminActionAuditor {

    private static final Logger log = LoggerFactory.getLogger(AdminActionAuditor.class);

    private final AuditService auditService;
    private final AuditActorResolver actorResolver;

    public AdminActionAuditor(AuditService auditService, AuditActorResolver actorResolver) {
        this.auditService = auditService;
        this.actorResolver = actorResolver;
    }

    /**
     * 管理面发起时记一条审计；开放面（TENANT）静默跳过（open_api_audit 已覆盖）。
     * 审计组装失败不影响业务——只记 ERROR。
     */
    public void record(String action, String resourceType, String resourceId, Map<String, ?> detail) {
        try {
            AuditActorResolver.Actor actor = actorResolver.resolve();
            if (AuditActorResolver.TYPE_TENANT.equals(actor.type())) {
                return;   // 开放面：open_api_audit 已按调用全量覆盖，不双写哈希链
            }
            auditService.record(AuditService.AuditContext.of(
                    null, null,
                    org.slf4j.MDC.get(com.newcoupon.infrastructure.web.TraceIdFilter.TRACE_ID),
                    action, resourceType, resourceId,
                    null, detail == null ? null : AuditService.serialize(detail),
                    currentIp(), 0,
                    actor.type(), actor.actorId(), actor.sessionFingerprint()));
        } catch (Exception e) {
            log.error("admin action audit failed: action={} resourceId={}", action, resourceId, e);
        }
    }

    /** 当前请求来源 IP（非 HTTP 上下文返回 null——管理面写动作都是 HTTP 进来的，防御而已）。 */
    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
