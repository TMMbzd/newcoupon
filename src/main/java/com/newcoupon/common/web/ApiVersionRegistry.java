package com.newcoupon.common.web;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * API 版本注册表（V6 2B-2，ADR-0022）——开放面 {@code /open/{版本}/...} 里"哪些版本存在、
 * 各自处于什么生命周期"的唯一事实源。
 *
 * <p><b>它是什么</b>：一张纯代码表（无 DB、无配置文件、无管理接口）。开放面认证过滤器
 * （OpenApiAuthFilter 第 1 步）每次请求都先问它"这个路径的版本段登记了吗"：
 * 登记了 → 按该版本的元数据继续认证；没登记（包括尚未启用的 v2）→ 6010 拒绝，
 * 响应体 {@code data.supportedVersions} 告诉接入方现在有哪些版本可用。
 *
 * <p><b>为什么是代码表而不是配置面</b>（ADR-0022 的裁定）：版本的存在与否决定**请求能不能进门**，
 * 属于代码发版才能改变的事实——放进配置/DB 等于把"开一个新 API 面"的权力交给运维改一行配置，
 * 而新版本必然伴随新代码（新 Controller、新字段），"配置先开、代码后到"只会制造一个 404 面。
 * 配置适合放**参数**（如废弃日期可以在公告时改），不适合放**契约存在性**；当前版本数量为 1，
 * 更没有引入配置面的收益。
 *
 * <p><b>版本段语义（与既有行为逐字节一致）</b>：旧实现是 {@code path.startsWith("/open/v1/")}，
 * 即 {@code /open/v1}（无尾斜杠）会被拒 6010。本注册表保持完全相同的边界：
 * {@code /open/v1} 与 {@code /open/v1/} 的差别——前者无尾斜杠 → 不解析（6010）；
 * 后者 restPath 为空串 → 版本检查放行、由认证链后续步骤拒绝。改掉这个语义等于改变现有对外行为。
 *
 * <p><b>DEPRECATED 的三个响应头</b>：认证通过且版本已宣布废弃时，响应加
 * {@code Deprecation}（RFC 9745，Unix 秒）、{@code Sunset}（RFC 8594，HTTP-date）、
 * {@code Link; rel="deprecation"}`（迁移文档地址）。当前 v1=ACTIVE 不触发，但生成逻辑真实存在
 * （见 {@link #deprecationHeaders}），由单测直接构造 DEPRECATED 版本钉住格式。
 */
@Component
public class ApiVersionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionRegistry.class);

    /** 开放面的路径前缀：/open/ 之后的第一段就是版本段（如 /open/v1/coupons 的 "v1"） */
    static final String OPEN_PREFIX = "/open/";

    /**
     * 版本表：注册顺序即 {@link #supportedVersions()} 的返回顺序（将来 v2 登记时追加在 v1 之后）。
     * LinkedHashMap 保证顺序稳定——supportedVersions 要进 6010 响应体，顺序跳变会让
     * 接入方的快照测试误报。
     */
    private static final Map<String, ApiVersion> VERSIONS = buildRegistry();

    private static Map<String, ApiVersion> buildRegistry() {
        Map<String, ApiVersion> table = new LinkedHashMap<>();
        // 当前唯一登记的版本：v1 处于 ACTIVE。deprecation/sunset/link 三项只在宣布废弃时填
        // （格式：ISO 本地日期 yyyy-MM-dd；link 是迁移指南的绝对 URL）。
        table.put("v1", new ApiVersion("v1", ApiVersion.VersionStatus.ACTIVE, null, null, null));
        return java.util.Collections.unmodifiableMap(table);
    }

    /**
     * 解析开放面路径的版本段。
     *
     * <p>规则：{@code /open/{seg}/{rest...}}——取 {@code /open/} 之后、下一个斜杠之前的一段当版本段，
     * 段之后的全部（不含开头的斜杠）作为 restPath（如 {@code coupons/receive}）。
     *
     * @return 登记过的版本 → {@link ResolvedVersion}；未登记（含 v2）、路径不带尾斜杠
     *         （{@code /open/v1}）、或不以 {@code /open/} 开头 → empty——调用方据此拒 6010
     */
    public Optional<ResolvedVersion> resolve(String servletPath) {
        if (servletPath == null || !servletPath.startsWith(OPEN_PREFIX)) {
            return Optional.empty();
        }
        int segStart = OPEN_PREFIX.length();
        int slash = servletPath.indexOf('/', segStart);
        if (slash < 0) {
            // "/open/v1"（无尾斜杠）不解析：旧实现 startsWith("/open/v1/") 对它同样是 false → 6010。
            // 保持拒绝，不因注册表引入新的放行路径。
            return Optional.empty();
        }
        String segment = servletPath.substring(segStart, slash);
        ApiVersion version = VERSIONS.get(segment);
        if (version == null) {
            // 版本段拼写未登记（v2、v99、空串、v1x……都一样）：调用方统一拒 6010。
            return Optional.empty();
        }
        return Optional.of(new ResolvedVersion(version, servletPath.substring(slash + 1)));
    }

    /**
     * 所有登记版本的 segment 列表（ACTIVE 与 DEPRECATED 都算"支持中"）。
     *
     * <p>DEPRECATED 只是**宣布了**废弃日期——Sunset 日期之前它仍在服务，接入方仍可调用；
     * 把它从"支持中"拿掉会让 6010 的引导信息在迁移期里说谎（明明能用却说只有 v2）。
     */
    public List<String> supportedVersions() {
        return new ArrayList<>(VERSIONS.keySet());
    }

    /**
     * 查一个版本段当前是否登记（供测试与文档类工具核对，热路径不走它）。
     */
    public Optional<ApiVersion> find(String segment) {
        return Optional.ofNullable(VERSIONS.get(segment));
    }

    /**
     * 生成 DEPRECATED 版本的三件套响应头（纯函数：只依赖入参，不读时钟、不读状态）。
     *
     * <p>规则（RFC 9745 / RFC 8594 / RFC 8288 Link）：
     * <ul>
     *   <li>{@code Deprecation: @<unix秒>}——RFC 9745 的结构化语法，@ 前缀 + Unix 时间戳。
     *       日期字段（yyyy-MM-dd）按 UTC 当天 00:00:00 折算成秒；</li>
     *   <li>{@code Sunset: <HTTP-date>}`——RFC 8594，IMF-fixdate 格式（GMT）。
     *       日期字段按 UTC 当天 23:59:59 折算（"这一天结束"是下线时刻的自然语义）；</li>
     *   <li>{@code Link: <link>; rel="deprecation"}`——指向迁移指南。</li>
     * </ul>
     *
     * <p>ACTIVE 版本返回空 Map。DEPRECATED 但某字段为空 → 跳过该头（少一个建议性头
     * 好过让整个请求 500）；日期串解析失败同样跳过并 WARN——这三件套是**建议性**元数据，
     * 任何坏值都不该影响调用本身。抽成纯函数是为了单测能不依赖注册表全局状态直接钉格式。
     */
    public static Map<String, String> deprecationHeaders(ApiVersion version) {
        if (version == null || version.status() != ApiVersion.VersionStatus.DEPRECATED) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (version.deprecationDate() != null) {
            parseDate(version.deprecationDate()).ifPresent(
                    date -> headers.put("Deprecation",
                            "@" + date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()));
        }
        if (version.sunsetDate() != null) {
            parseDate(version.sunsetDate()).ifPresent(
                    date -> headers.put("Sunset", SUNSET_FORMAT.format(
                            date.atTime(23, 59, 59).atZone(ZoneOffset.UTC))));
        }
        if (version.link() != null && !version.link().isBlank()) {
            headers.put("Link", "<" + version.link() + ">; rel=\"deprecation\"");
        }
        return headers;
    }

    /** Sunset 用的 IMF-fixdate 格式（RFC 9110）：EEE, dd MMM yyyy HH:mm:ss 'GMT'，Locale 必须英文 */
    private static final DateTimeFormatter SUNSET_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                    java.util.Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private static Optional<LocalDate> parseDate(String raw) {
        try {
            return Optional.of(LocalDate.parse(raw));
        } catch (RuntimeException e) {
            // 注册表是代码表，日期写错是程序员错误：WARN 让它被发现，但拒绝服务比坏头更糟
            log.warn("API 版本日期字段无法解析（已跳过该响应头）：{}", raw);
            return Optional.empty();
        }
    }

    /** resolve 的结果：命中的版本元数据 + 去掉 /open/{版本}/ 前缀后的剩余路径（如 coupons/receive） */
    public record ResolvedVersion(ApiVersion version, String restPath) {
    }

    /** 表驱动注册表里一条版本的登记信息 */
    public record ApiVersion(String segment, VersionStatus status,
                             String deprecationDate, String sunsetDate, String link) {

        public ApiVersion {
            // 登记时立即校验日期格式（fail-fast）：注册表是静态代码表，写错日期会在类加载时
            // 就抛出来，而不是等第一次给 DEPRECATED 版本生成头时才靠 WARN 被发现
            // （deprecationHeaders 里的 WARN-跳过只是纵深防御，不该被走到）。
            if (deprecationDate != null) {
                requireIsoDate(deprecationDate);
            }
            if (sunsetDate != null) {
                requireIsoDate(sunsetDate);
            }
        }

        private static void requireIsoDate(String raw) {
            try {
                LocalDate.parse(raw);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "ApiVersion 日期字段必须是 ISO 格式 yyyy-MM-dd，实际是：" + raw);
            }
        }

        /** 版本生命周期：ACTIVE=正常服务；DEPRECATED=已宣布废弃（Sunset 前仍在服务） */
        public enum VersionStatus { ACTIVE, DEPRECATED }
    }
}
