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
 * 管理面鉴权判据的结构性守卫（2026-10-10 第 0 环 0-3 项；V6 2A-2 起 /api GET 全认证翻面，
 * 判据从"非 GET 默认拒绝"升级为"所有方法默认拒绝"——破坏性变更公告：
 * docs/runbook/管理面认证翻面.md，回滚开关 coupon.security.api-read-auth-enabled）。
 *
 * <p>守的是什么：2A-2 翻面后管理面（/api/**、/console**）**没有匿名方法**——
 * GET 一样要"旧令牌或会话"。本测试保证两件事：
 * <ol>
 *   <li><b>语义</b>：任意未豁免的 /api、/console 路径（不分方法）都被判"需保护"。
 *       豁免面只有两块：C 端业务写口（{@code BUSINESS_WRITE_PATHS}，防线是风控/幂等/状态机）
 *       与身份自服务（/console/api/auth/**，登录自举的前提）。/open 走签名链不经本过滤器。
 *       actuator 是另一条链（health/info 匿名、prometheus/metrics 要 Basic），不归本过滤器管，
 *       也不出现在 RequestMappingHandlerMapping 里。</li>
 *   <li><b>结构</b>：遍历当前全部 /api、/console 映射（**含 GET**），任何接口必居
 *       "受保护"或"显式豁免"之一——将来任何人加出第三态（既不受防线也不在豁免里的
 *       /api、/console 接口）测试直接红。V6 2D 起 /console 的受保护前缀收窄为
 *       /console/api/**（静态资源匿名），因此**在 /console/api/ 之外新加 /console
 *       控制器映射同样会红**——数据接口必须落 /console/api/，静态资源不走控制器
 *       （classpath 静态托管），两条路不许混。</li>
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
    @DisplayName("语义：未豁免的 /api、/console 路径不分方法一律需保护；豁免面=业务白名单+身份自服务")
    void semantics_defaultDenyForAllMethods() {
        // 未登记的新路径（任意方法，哪怕是将来新增的只读管理接口）默认受保护——
        // 翻面前 GET 在这里应为 false，这正是 2A-2 要钉死的语义变化
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/anything-new")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("PUT", "/api/some/module/1")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("DELETE", "/api/some/module/1")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/api/anything-new")).isTrue();

        // 翻面钉住：管理面 GET 与写方法同一判据（只读面匿名放行的时代结束）
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/api/templates")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/api/apps")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/api/ping")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/console/api/anything")).isTrue();

        // V6 2D 裁定：/console 下只有 /console/api/** 是数据面；其余是控制台静态资源
        // （index.html/js/css），匿名可取是设计——敏感数据全在 API 认证后面
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/console/")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/console/index.html")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/console/assets/index-abc.js")).isFalse();

        // 业务面写接口白名单（C 端功能口，各有自己的防线）——翻面不动这张表
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/receive")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/receive-async")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/lock")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/confirm")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/api/coupons/release")).isFalse();

        // 身份自服务端点（登录/登出/me 不分方法）：防线是 CSRF+锁定+会话，不是管理令牌
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/console/api/auth/login")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/console/api/auth/me")).isFalse();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/console/api/auth/logout")).isFalse();

        // 开放面走签名链（/open 前缀不经本过滤器）
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/open/v1/calculate")).isFalse();

        // V6 5B 刀 A：/internal/admin/** 归管理面——摘流量（drain/undrain）是
        // "把实例请出集群"的能力，与 /api 写口同一认证判据（旧令牌或会话）
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/internal/admin/drain")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("POST", "/internal/admin/undrain")).isTrue();
        assertThat(LegacyAdminTokenAuthenticationFilter.isProtected("GET", "/internal/admin/anything")).isTrue();
    }

    @Test
    @DisplayName("结构：全部 /api、/console、/internal 映射（含 GET）必居'受保护'或'显式豁免'之一")
    void structural_everyMappingProtectedOrExempted() {
        List<String> violations = new ArrayList<>();

        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            // 方法集为空 = 未限定（GET/POST/... 全都有），按"全方法"从严对待；
            // 翻面后 GET 也在守卫范围内——"只读接口"不再是可以豁免的理由
            List<RequestMethod> checkedMethods = methods.isEmpty()
                    ? List.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                            RequestMethod.DELETE, RequestMethod.PATCH)
                    : List.copyOf(methods);

            Set<String> paths = info.getPathPatternsCondition() != null
                    ? info.getPathPatternsCondition().getPatternValues()
                    : info.getDirectPaths();

            for (String path : paths) {
                if (!(path.startsWith("/api/") || path.startsWith("/console") || path.startsWith("/internal/"))) {
                    continue;
                }
                for (RequestMethod method : checkedMethods) {
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
                .as("既不受管理令牌保护、也不在任何显式豁免里的 /api、/console 接口（不应存在）")
                .isEmpty();
    }
}
