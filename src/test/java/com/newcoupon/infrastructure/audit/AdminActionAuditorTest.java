package com.newcoupon.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.application.service.AuditService;
import com.newcoupon.common.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 管理面写动作审计闸门单测（V6 2C 审查 M3）：
 * 开放面（TENANT）不双写；管理面会话（ADMIN）与旧令牌（SYSTEM）都留痕。
 */
class AdminActionAuditorTest {

    private final AuditService auditService = mock(AuditService.class);
    private final AuditActorResolver resolver = mock(AuditActorResolver.class);
    private final AdminActionAuditor auditor = new AdminActionAuditor(auditService, resolver);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContext.clear();
    }

    @Test
    @DisplayName("开放面（TENANT）静默跳过：open_api_audit 已覆盖，不双写哈希链")
    void tenantCallsSkipped() {
        when(resolver.resolve()).thenReturn(
                new AuditActorResolver.Actor("TENANT", "app-1", null));

        auditor.record("TEMPLATE_PUBLISH", "TEMPLATE", "1", null);

        verify(auditService, never()).record(any());
    }

    @Test
    @DisplayName("管理面会话（ADMIN）留痕：actor 三列带管理员身份与会话指纹")
    void adminSessionRecorded() {
        when(resolver.resolve()).thenReturn(
                new AuditActorResolver.Actor("ADMIN", "7", "abc123def4567890"));

        auditor.record("APP_ROTATE_SECRET", "OPEN_APP", "app-1", java.util.Map.of("k", "v"));

        ArgumentCaptor<AuditService.AuditContext> captor =
                ArgumentCaptor.forClass(AuditService.AuditContext.class);
        verify(auditService).record(captor.capture());
        AuditService.AuditContext ctx = captor.getValue();
        assertThat(ctx.action()).isEqualTo("APP_ROTATE_SECRET");
        assertThat(ctx.resourceId()).isEqualTo("app-1");
        assertThat(ctx.actorType()).isEqualTo("ADMIN");
        assertThat(ctx.actorId()).isEqualTo("7");
        assertThat(ctx.sessionId()).isEqualTo("abc123def4567890");
        assertThat(ctx.hashVersion()).isEqualTo(AuditService.HASH_VERSION_CURRENT);
    }

    @Test
    @DisplayName("旧管理令牌（SYSTEM，共享机器凭据）动作照记——个人身份缺失不是不留痕的理由")
    void legacyTokenSystemActorStillRecorded() {
        // 真实场景：X-Admin-Token 过滤器把 principal 设为字符串，resolver 判 SYSTEM
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "legacy-admin-token", null, java.util.List.of()));
        RequestContext.clear();   // 无 appId 上下文（/api 面）
        AuditActorResolver realResolver = new AuditActorResolver();
        AdminActionAuditor realAuditor = new AdminActionAuditor(auditService, realResolver);

        realAuditor.record("APP_CREATE", "OPEN_APP", "app-2", null);

        ArgumentCaptor<AuditService.AuditContext> captor =
                ArgumentCaptor.forClass(AuditService.AuditContext.class);
        verify(auditService).record(captor.capture());
        assertThat(captor.getValue().actorType()).isEqualTo("SYSTEM");
        assertThat(captor.getValue().actorId()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("审计组装抛异常不影响业务：record 不向外抛")
    void auditFailureSwallowed() {
        when(resolver.resolve()).thenThrow(new IllegalStateException("boom"));
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> auditor.record("APP_CREATE", "OPEN_APP", "app-3", null))
                .doesNotThrowAnyException();
        verify(auditService, never()).record(any());
    }

}
