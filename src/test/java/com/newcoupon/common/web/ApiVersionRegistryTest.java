package com.newcoupon.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.common.web.ApiVersionRegistry.ApiVersion;
import com.newcoupon.common.web.ApiVersionRegistry.ResolvedVersion;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ApiVersionRegistry 的纯单测（V6 2B-2）——不起 Spring 容器，直接 new。
 *
 * <p>钉住三组事实：
 * <ol>
 *   <li><b>边界语义与旧实现逐字节一致</b>：旧判据是 {@code path.startsWith("/open/v1/")}，
 *       换成注册表解析后，{@code /open/v1}（无尾斜杠）必须仍然拒绝、{@code /open/v1/}（空
 *       restPath）必须仍然放行到认证链后续步骤——这条边界是对外行为，不是实现细节；</li>
 *   <li><b>6010 的引导数据</b>：supportedVersions 含 DEPRECATED 版本（迁移期仍在服务）、
 *       顺序为登记顺序；</li>
 *   <li><b>DEPRECATED 三件套头的格式</b>：RFC 9745 的 {@code @unix秒}、RFC 8594 的
 *       IMF-fixdate、RFC 8288 的 Link——v1 当前 ACTIVE 不触发，所以只能靠这里直接构造
 *       DEPRECATED 版本钉格式（等真宣布废弃时格式已经是被测过的）。</li>
 * </ol>
 */
class ApiVersionRegistryTest {

    private final ApiVersionRegistry registry = new ApiVersionRegistry();

    // ------------------------------------------------------------------
    // resolve：正常路径与边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正例：/open/v1/coupons/receive → v1 + restPath=coupons/receive")
    void resolvesV1Path() {
        Optional<ResolvedVersion> resolved = registry.resolve("/open/v1/coupons/receive");

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().version().segment()).isEqualTo("v1");
        assertThat(resolved.orElseThrow().version().status()).isEqualTo(ApiVersion.VersionStatus.ACTIVE);
        // restPath 不带开头斜杠——与旧 path.substring(V1_PREFIX.length()) 的输出一致
        assertThat(resolved.orElseThrow().restPath()).isEqualTo("coupons/receive");
    }

    @Test
    @DisplayName("边界：/open/v1（无尾斜杠）→ 拒（与旧 startsWith 语义一致，不是新放行）")
    void versionWithoutTrailingSlash_rejected() {
        // 旧实现："/open/v1".startsWith("/open/v1/") == false → 6010。
        // 注册表实现必须保持同一个答案——这条路径从来不是合法入口。
        assertThat(registry.resolve("/open/v1")).isEmpty();
    }

    @Test
    @DisplayName("边界：/open/v1/（空 restPath）→ 版本检查放行，restPath 为空串")
    void trailingSlashWithEmptyRest_passesVersionCheck() {
        // 旧实现："/open/v1/".startsWith("/open/v1/") == true → 版本检查通过，请求继续走
        // 签名头/验签等步骤（最终大概率 6001/6003）。注册表必须放行同样的请求。
        Optional<ResolvedVersion> resolved = registry.resolve("/open/v1/");
        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().restPath()).isEmpty();
    }

    @Test
    @DisplayName("未注册版本：v2 / v1x / 空段 / 非 /open 前缀 → 一律 empty（调用方拒 6010）")
    void unregisteredSegments_rejected() {
        assertThat(registry.resolve("/open/v2/coupons")).as("v2 尚未登记").isEmpty();
        assertThat(registry.resolve("/open/v1x/coupons")).as("前缀撞名不算 v1").isEmpty();
        assertThat(registry.resolve("/open//coupons")).as("空版本段").isEmpty();
        assertThat(registry.resolve("/api/coupons")).as("非开放面前缀").isEmpty();
        assertThat(registry.resolve("/open/")).as("只有前缀").isEmpty();
        assertThat(registry.resolve(null)).as("防御：null 不炸").isEmpty();
    }

    // ------------------------------------------------------------------
    // supportedVersions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("supportedVersions：当前 = [v1]，登记顺序返回")
    void supportedVersionsListsAllRegistered() {
        assertThat(registry.supportedVersions()).containsExactly("v1");
    }

    // ------------------------------------------------------------------
    // deprecationHeaders（纯函数）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVE 版本不产生废弃头（当前 v1 的实际路径）")
    void activeVersion_hasNoDeprecationHeaders() {
        assertThat(ApiVersionRegistry.deprecationHeaders(
                registry.find("v1").orElseThrow())).isEmpty();
    }

    @Test
    @DisplayName("DEPRECATED 版本：三件套头格式 = RFC 9745 @unix秒 / RFC 8594 IMF-fixdate / Link")
    void deprecatedVersion_rendersThreeHeaders() {
        ApiVersion deprecated = new ApiVersion("v1", ApiVersion.VersionStatus.DEPRECATED,
                "2026-11-01",            // 废弃公告日
                "2027-11-30",            // 计划下线日
                "https://docs.example.com/migrating-to-v2");

        Map<String, String> headers = ApiVersionRegistry.deprecationHeaders(deprecated);

        // Deprecation：RFC 9745 结构化语法 @<unix秒>；日期按 UTC 当天 00:00:00 折算
        assertThat(headers.get("Deprecation"))
                .isEqualTo("@" + java.time.LocalDate.parse("2026-11-01")
                        .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond());

        // Sunset：IMF-fixdate（RFC 9110 的 HTTP-date 形态）：英文星期/月份 + 两位日期 + GMT；
        // 日期按 UTC 当天 23:59:59 折算（"这一天结束"下线）。2027-11-30 是周二。
        assertThat(headers.get("Sunset")).isEqualTo("Tue, 30 Nov 2027 23:59:59 GMT");

        // Link：RFC 8288，rel=deprecation 指迁移文档
        assertThat(headers.get("Link"))
                .isEqualTo("<https://docs.example.com/migrating-to-v2>; rel=\"deprecation\"");
    }

    @Test
    @DisplayName("DEPRECATED 但字段残缺：有哪个头生成哪个，缺的不炸、不产生空值头")
    void deprecatedVersion_withMissingFields_rendersOnlyKnownHeaders() {
        ApiVersion onlySunset = new ApiVersion("v1", ApiVersion.VersionStatus.DEPRECATED,
                null, "2027-11-30", null);

        assertThat(ApiVersionRegistry.deprecationHeaders(onlySunset))
                .containsOnlyKeys("Sunset");
    }

    @Test
    @DisplayName("登记时日期格式校验 fail-fast：非 yyyy-MM-dd 直接抛（代码表错误在类加载期暴露）")
    void malformedDate_failsFastAtConstruction() {
        assertThatThrownBy(() -> new ApiVersion("v9", ApiVersion.VersionStatus.DEPRECATED,
                "2026/11/01", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }
}
