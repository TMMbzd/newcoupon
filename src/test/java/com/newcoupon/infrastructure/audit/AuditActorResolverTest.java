package com.newcoupon.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcoupon.common.context.RequestContext;
import com.newcoupon.infrastructure.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * actor 解析器单测（V6 2C）：三段优先级各自命中 + 互不误判。
 *
 * <p>不启 Spring 上下文——SecurityContextHolder/RequestContextHolder/RequestContext
 * 都是 ThreadLocal，直接摆好再读，是这类"纯上下文判定"逻辑最便宜的测法。
 */
class AuditActorResolverTest {

    private final AuditActorResolver resolver = new AuditActorResolver();

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        RequestContext.clear();
    }

    @Test
    @DisplayName("管理面会话（AdminPrincipal）→ ADMIN + userId + 当前会话 id")
    void adminSessionWins() {
        ServletRequestAttributes attrs = mock(ServletRequestAttributes.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(attrs.getRequest()).thenReturn(request);
        when(request.getSession(false)).thenReturn(session);
        when(session.getId()).thenReturn("sess-123");
        RequestContextHolder.setRequestAttributes(attrs);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(7L, "alice"), null, java.util.List.of()));

        AuditActorResolver.Actor actor = resolver.resolve();

        assertThat(actor.type()).isEqualTo("ADMIN");
        assertThat(actor.actorId()).isEqualTo("7");
        assertThat(actor.sessionId()).isEqualTo("sess-123");
    }

    @Test
    @DisplayName("开放面（RequestContext.appId）→ TENANT + appId，会话为空")
    void tenantAppIdSecond() {
        RequestContext.set(new RequestContext("app-01", "trace-1", Long.MAX_VALUE, false));

        AuditActorResolver.Actor actor = resolver.resolve();

        assertThat(actor.type()).isEqualTo("TENANT");
        assertThat(actor.actorId()).isEqualTo("app-01");
        assertThat(actor.sessionId()).isNull();
    }

    @Test
    @DisplayName("两者皆无（后台任务）→ SYSTEM/SYSTEM")
    void systemFallback() {
        AuditActorResolver.Actor actor = resolver.resolve();

        assertThat(actor.type()).isEqualTo("SYSTEM");
        assertThat(actor.actorId()).isEqualTo("SYSTEM");
        assertThat(actor.sessionId()).isNull();
    }

    @Test
    @DisplayName("非 AdminPrincipal 的认证身份（旧令牌/开放面适配器/匿名）不冒充管理员：落到 TENANT 或 SYSTEM")
    void nonAdminPrincipalDoesNotImpersonateAdmin() {
        // 旧管理令牌的 principal 是字符串，且 /api 无 appId 上下文 → SYSTEM
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "legacy-admin-token", null, java.util.List.of()));
        assertThat(resolver.resolve().type()).as("旧令牌不是个人身份").isEqualTo("SYSTEM");

        // 开放面适配器的 principal 是 "open:{appId}"，但 RequestContext 有 appId → TENANT
        RequestContext.set(new RequestContext("app-02", "trace-2", Long.MAX_VALUE, false));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "open:app-02", null, java.util.List.of()));
        AuditActorResolver.Actor actor = resolver.resolve();
        assertThat(actor.type()).isEqualTo("TENANT");
        assertThat(actor.actorId()).isEqualTo("app-02");
    }

    @Test
    @DisplayName("AdminPrincipal 但无 HTTP 会话（非 HTTP 上下文）→ ADMIN，sessionId 空")
    void adminWithoutSessionStillAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(9L, "bob"), null, java.util.List.of()));

        AuditActorResolver.Actor actor = resolver.resolve();

        assertThat(actor.type()).isEqualTo("ADMIN");
        assertThat(actor.actorId()).isEqualTo("9");
        assertThat(actor.sessionId()).isNull();
    }
}
