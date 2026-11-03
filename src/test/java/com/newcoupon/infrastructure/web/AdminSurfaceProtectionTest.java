package com.newcoupon.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.integration.ShardedIntegrationTestSupport;
import com.newcoupon.infrastructure.security.LegacyAdminTokenAuthenticationFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 管理面鉴权判据反转的结构性守卫（2026-10-10 第 0 环 0-3 项；V6 2A 起过滤器更名
 * LegacyAdminTokenAuthenticationFilter 并移入安全链，判据原样）。
 *
 * <p>守的是什么：旧判据（显式列举路径前缀才保护）下，"新增管理写接口忘了登记 = 无鉴权写口"
 * 是结构性缺口。新判据（非 GET 默认拒绝 + 显式豁免）下，本测试保证两件事：
 * <ol>
 *   <li><b>语义</b>：任意未登记的 /api、/console 写路径都被判"需保护"（旧判据在此红——
 *       红灯证据见第 0 环文档包）；GET 与业务白名单不受影响；/open 走签名链不经本过滤器。</li>
 *   <li><b>结构</b>：遍历当前全部写映射，任何 /api、/console 写接口必居"受保护"或
 *       "显式豁免（业务白名单 / 身份自服务）"三态之一——将来任何人加出的第四态
 *       （既不受防线也不在豁免里的 /api 写口）测试直接红。</li>
 * </ol>
 */
class AdminSurfaceProtectionTest extends ShardedIntegrationTestSupport {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Test
    @DisplayName("语义：未登记的 /api、/console 写路径默认需保护；GET/业务白名单/open/身份自服务不受影响")
    void semantics_defaultDenyForUnlistedWritePaths() {
        // 未登记的新路径（哪怕是将来新增的任意管理接口）默认受保护——旧判据在此为 false（红灯点）
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/anything-new")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("PUT", "/api/some/module/1")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("DELETE", "/api/some/module/1")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/console/some/admin-page")).isTrue();

        // 只读不设防
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/api/templates")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/api/apps")).isFalse();

        // 业务面写接口白名单（C 端功能口，各有自己的防线）
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/receive")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/receive-async")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/lock")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/confirm")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/release")).isFalse();

        // 身份自服务端点（V6 2A）：登录/登出/me 的防线是 CSRF+锁定+会话，不是管理令牌
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/console/api/auth/login")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/console/api/auth/logout")).isFalse();

        // 开放面走签名链（/open 前缀不经本过滤器）
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/open/v1/calculate")).isFalse();
    }

    @Test
    @DisplayName("结构：全部 /api、/console 写映射必居'受保护'或'显式豁免'三态之一")
    void structural_everyWriteMappingProtectedOrExempted() {
        List<String> violations = new ArrayList<>();

        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            // 方法集为空 = 未限定（含 GET 也含 POST），按"可写"从严对待
            List<RequestMethod> writeMethods = methods.isEmpty()
                    ? List.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH)
                    : methods.stream()
                            .filter(m -> !RequestMethod.GET.equals(m))
                            .toList();
            if (writeMethods.isEmpty()) {
                return;
            }

            Set<String> paths = info.getPathPatternsCondition() != null
                    ? info.getPathPatternsCondition().getPatternValues()
                    : info.getDirectPaths();

            for (String path : paths) {
                if (!(path.startsWith("/api/") || path.startsWith("/console"))) {
                    continue;
                }
                for (RequestMethod method : writeMethods) {
                    boolean protectedByFilter =
                            LegacyAdminTokenAuthenticationFilter.isProtected(method.name(), path);
                    boolean whitelisted =
                            LegacyAdminTokenAuthenticationFilter.BUSINESS_WRITE_PATHS.contains(path);
                    boolean authService = path.startsWith(
                            LegacyAdminTokenAuthenticationFilter.AUTH_SERVICE_PREFIX);
                    if (!protectedByFilter && !whitelisted && !authService) {
                        violations.add(method + " " + path
                                + "（handler=" + handler.getBeanType().getSimpleName() + "）");
                    }
                }
            }
        });

        assertThat(violations)
                .as("既不受管理令牌保护、也不在任何显式豁免里的 /api、/console 写接口（不应存在）")
                .isEmpty();
    }
}
