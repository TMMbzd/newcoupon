package com.newcoupon.infrastructure.audit;

import com.newcoupon.common.context.RequestContext;
import com.newcoupon.infrastructure.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * actor 解析器（V6 2C）：按固定优先级回答"这条审计是谁干的"。
 *
 * <p>三段判定（先管理面后开放面最后兜底，顺序不可换）：
 * <ol>
 *   <li><b>ADMIN</b>——SecurityContext 里是 {@link AdminPrincipal}（控制台会话）：
 *       actor_id=userId，session_id=当前请求的会话 id（拿不到为空——非 HTTP 上下文
 *       或会话刚失效的极窄窗口）。旧管理令牌的 principal 是字符串 "legacy-admin-token"、
 *       开放面是 "open:{appId}"，都不是 AdminPrincipal，不会误判成管理员；</li>
 *   <li><b>TENANT</b>——RequestContext 有 appId（HMAC 验签过滤器写入）：actor_id=appId；</li>
 *   <li><b>SYSTEM</b>——两者皆无（后台任务，或没有个人身份的机器凭据如旧管理令牌）：
 *       actor_id=SYSTEM。"找不到人"时也要留下"是系统/机器干的"这个事实。</li>
 * </ol>
 *
 * <p>四值里的 USER 当前不产出：C 端用户的调用审计在 open_api_audit 专表（带请求级细节），
 * 不进 audit_log；列值预留，避免将来引入时改列注释与文档口径。
 *
 * <p>与六要素的分工：appId/userId 是"哪个接入方调的 / 动的是哪个业务用户的东西"，
 * actor 三列是"发起操作的身份是谁"——审计要同时回答两组问题，缺一组都有盲区。
 */
@Component
public class AuditActorResolver {

    /** actor 三元组：type 四值之一（ADMIN/TENANT/USER/SYSTEM），id 与 sessionId 可空。 */
    public record Actor(String type, String actorId, String sessionId) {
    }

    public static final String TYPE_ADMIN = "ADMIN";
    public static final String TYPE_TENANT = "TENANT";
    public static final String TYPE_SYSTEM = "SYSTEM";

    /** 按优先级解析当前线程的 actor（管理面会话 → 开放面应用 → 系统）。 */
    public Actor resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal admin) {
            return new Actor(TYPE_ADMIN, String.valueOf(admin.getUserId()), currentSessionId());
        }
        String appId = RequestContext.current().appId();
        if (appId != null) {
            return new Actor(TYPE_TENANT, appId, null);
        }
        return new Actor(TYPE_SYSTEM, TYPE_SYSTEM, null);
    }

    /**
     * 当前请求的会话 id：管理面会话请求里 Spring Session 已把真实 id 写在 HttpSession 上
     * （登录时 changeSessionId 轮换过）。getSession(false)——只为读 id，不凭空建会话；
     * 非 HTTP 上下文 / 无会话返回 null。
     */
    private String currentSessionId() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest request = attrs.getRequest();
                jakarta.servlet.http.HttpSession session = request.getSession(false);
                return session == null ? null : session.getId();
            }
        } catch (IllegalStateException ignored) {
            // 会话已失效（invalidate 之后）：id 读不到就留空，actor 身份仍由前两列回答
        }
        return null;
    }
}
