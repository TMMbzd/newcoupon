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
 *
 * <p><b>会话列存指纹不存原值（审查 C2）</b>：session_id 原值就是 SESSION Cookie
 * 凭据本身——审计行能被持 audit:read 的低权限角色查到，原值入库等于把高权限账号
 * 的会话凭据递给低权限角色（30 分钟空闲/8 小时绝对有效期内可当 Cookie 劫持）。
 * 这里统一转 SHA-256 指纹（前 16 hex）：同会话多行可关联、跨会话可区分，但还原
 * 凭据不可行（SHA-256 无逆，sessionId 是 UUID 级熵，字典也炸不动）。
 */
@Component
public class AuditActorResolver {

    /** actor 三元组：type 四值之一（ADMIN/TENANT/USER/SYSTEM）；sessionFingerprint 非凭据原值。 */
    public record Actor(String type, String actorId, String sessionFingerprint) {
    }

    public static final String TYPE_ADMIN = "ADMIN";
    public static final String TYPE_TENANT = "TENANT";
    public static final String TYPE_SYSTEM = "SYSTEM";

    /** 按优先级解析当前线程的 actor（管理面会话 → 开放面应用 → 系统）。 */
    public Actor resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal admin) {
            return new Actor(TYPE_ADMIN, String.valueOf(admin.getUserId()), currentSessionFingerprint());
        }
        String appId = RequestContext.current().appId();
        if (appId != null) {
            return new Actor(TYPE_TENANT, appId, null);
        }
        return new Actor(TYPE_SYSTEM, TYPE_SYSTEM, null);
    }

    /**
     * 会话 id → 指纹（SHA-256 前 16 hex，null 透传）。所有"要进审计行"的会话标识
     * 一律过这里——包括 AdminSessionService 手工组装的审计（登录/登出/会话终止），
     * 谁绕过谁就是把凭据原值写进可被低权限角色读到的表。
     */
    public static String fingerprint(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 当前请求会话的指纹：管理面会话请求里 Spring Session 已把真实 id 写在 HttpSession 上
     * （登录时 changeSessionId 轮换过）。getSession(false)——只为读 id，不凭空建会话；
     * 非 HTTP 上下文 / 无会话返回 null。
     */
    private String currentSessionFingerprint() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest request = attrs.getRequest();
                jakarta.servlet.http.HttpSession session = request.getSession(false);
                return session == null ? null : fingerprint(session.getId());
            }
        } catch (IllegalStateException ignored) {
            // 会话已失效（invalidate 之后）：id 读不到就留空，actor 身份仍由前两列回答
        }
        return null;
    }
}
