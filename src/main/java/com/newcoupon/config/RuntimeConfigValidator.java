package com.newcoupon.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 运行时配置校验（Phase 1）——把"部署配错"提前到启动时暴露，而不是等锁超时/过期满天飞时误诊。
 *
 * <p><b>为什么需要它</b>：本项目的 SQL 同时混用两个时钟源——MySQL 的 {@code NOW()}
 * （过期扫描、锁超时任务、券有效期谓词）与 JVM 的 {@code LocalDateTime.now()}（服务层传入的
 * 时间参数）。`serverTimezone` 只告诉 Connector/J 如何解释时间，并<b>不会</b>把 MySQL 的
 * session {@code time_zone} 改成同一时区（Connector/J 的
 * {@code forceConnectionTimeZoneToSession} 默认 false）。生产容器默认 UTC、MySQL 用
 * {@code SYSTEM} 时，两边的墙钟会差 8 小时，表现为"券已过期却还能核销""超时任务永远扫不到"。
 *
 * <p><b>为什么不是启动即失败（dev）</b>：本地 WSL/测试容器的时区组合很多，严格失败会让
 * 开发环境无法启动。因此：dev/测试只 WARN；active profile 含 {@code prod} 或显式
 * {@code coupon.runtime.strict-config=true} 时失败并终止启动。
 *
 * <p><b>连接数预算</b>：业务数据源（ShardingSphere 的 ds0 Hikari）与异步 worker 的裸写池
 * 是两套独立的池，多实例时按实例数线性叠加；MySQL {@code max_connections} 被吃穿的症状是
 * 一片 "Too many connections"，而那时应用往往已经启动成功、看起来健康。这里只使用配置值，
 * 不实例化 {@code @Lazy} 的裸写池（否则会破坏它的延迟初始化）。
 *
 * <p><b>XXL-JOB</b>：token/appname 是"执行器能否注册到正确调度中心"的前提；同机多实例时
 * 端口与日志目录必须按实例区分。默认值 {@code newcoupon-executor}/{@code default_token}
 * 只适合本地，prod 严格模式会拒绝。
 *
 * <p>校验逻辑与 Spring 装配分离（{@link #evaluate} 是纯函数），单测不启动容器。
 */
@Component
public class RuntimeConfigValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigValidator.class);

    /**
     * 账单日时区：与 {@code DailyBillService} 当前硬编码的 {@code Asia/Shanghai} 保持一致。
     * 可配置化推迟到调度任务（daily bill/pricing 锁）合并后——目前账单 cron 也带 zone 常量。
     */
    public static final ZoneId BILL_ZONE = ZoneId.of("Asia/Shanghai");

    static final String DEFAULT_XXL_APPNAME = "newcoupon-executor";
    static final int DEFAULT_XXL_PORT = 9999;
    static final String DEFAULT_XXL_TOKEN = "default_token";

    /** 允许 ±1s：TIMESTAMPDIFF 与 JVM 取 offset 不在同一微秒，避免边界误报。 */
    private static final long OFFSET_TOLERANCE_SECONDS = 1L;

    private final DataSource dataSource;
    private final Environment environment;

    /** 留空 = 由 active profile 决定（含 prod 即严格）。 */
    @Value("${coupon.runtime.strict-config:}")
    private String strictConfig;

    @Value("${coupon.runtime.expected-instances:1}")
    private int expectedInstances;

    @Value("${coupon.runtime.instances-per-host:1}")
    private int instancesPerHost;

    @Value("${coupon.runtime.mysql-safety-connections:20}")
    private int safetyConnections;

    @Value("${coupon.runtime.mysql-max-utilization:0.8}")
    private double maxUtilization;

    @Value("${coupon.sharding.pool.max-size:20}")
    private int businessPoolMax;

    @Value("${coupon.issue.worker.raw-bulk-pool-size:4}")
    private int rawBulkPoolMax;

    @Value("${xxl.job.enabled:false}")
    private boolean xxlEnabled;

    @Value("${xxl.job.appname:newcoupon-executor}")
    private String xxlAppname;

    @Value("${xxl.job.port:9999}")
    private int xxlPort;

    @Value("${xxl.job.accessToken:}")
    private String xxlAccessToken;

    @Value("${xxl.job.logPath:/tmp/xxl-job/log}")
    private String xxlLogPath;

    private volatile Report lastReport = Report.empty();

    public RuntimeConfigValidator(DataSource dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    /** 最近一次校验结果（测试/诊断用）；容器启动前为空报告。 */
    public Report lastReport() {
        return lastReport;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Settings settings = settings();
        DbSnapshot snapshot = null;
        String probeError = null;
        try {
            snapshot = probe();
        } catch (RuntimeException | SQLException e) {
            probeError = e.toString();
        }

        Report report = evaluate(settings, snapshot, probeError);
        this.lastReport = report;

        for (String warning : report.warnings()) {
            log.warn("运行时配置校验（宽松模式，仅告警）：{}", warning);
        }
        for (String error : report.errors()) {
            log.error("运行时配置校验失败：{}", error);
        }
        if (!report.errors().isEmpty()) {
            throw new IllegalStateException("运行时配置校验失败（strict=" + settings.strict()
                    + "）：" + String.join("；", report.errors()));
        }
        log.info("运行时配置校验通过：strict={} checked={} warnings={}",
                settings.strict(), report.checked(), report.warnings().size());
    }

    // ------------------------------------------------------------------
    // 探测
    // ------------------------------------------------------------------

    private DbSnapshot probe() throws SQLException {
        String sql = "SELECT @@session.time_zone AS session_tz, @@global.time_zone AS global_tz, "
                + "@@system_time_zone AS system_tz, "
                + "TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW()) AS offset_seconds, "
                + "@@max_connections AS max_connections";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("配置探测查询没有返回行");
            }
            return new DbSnapshot(
                    rs.getString("session_tz"),
                    rs.getString("global_tz"),
                    rs.getString("system_tz"),
                    rs.getLong("offset_seconds"),
                    rs.getInt("max_connections"));
        }
    }

    private Settings settings() {
        return new Settings(
                resolveStrict(),
                expectedInstances,
                instancesPerHost,
                businessPoolMax,
                rawBulkPoolMax,
                safetyConnections,
                maxUtilization,
                xxlEnabled,
                xxlAppname,
                xxlPort,
                xxlAccessToken,
                xxlLogPath,
                ZoneId.systemDefault(),
                BILL_ZONE);
    }

    private boolean resolveStrict() {
        if (strictConfig != null && !strictConfig.isBlank()) {
            return Boolean.parseBoolean(strictConfig.trim());
        }
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile));
    }

    // ------------------------------------------------------------------
    // 纯校验逻辑（单测直接调用）
    // ------------------------------------------------------------------

    static Report evaluate(Settings settings, DbSnapshot snapshot, String probeError) {
        List<String> checked = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Consumer<String> violation = message -> {
            if (settings.strict()) {
                errors.add(message);
            } else {
                warnings.add(message);
            }
        };

        validateSettings(settings, violation);

        checked.add("mysql-probe");
        if (snapshot == null) {
            violation.accept("数据库配置探测失败，无法校验时区与连接数预算"
                    + (probeError == null ? "" : "：" + probeError));
        } else {
            checked.add("timezone");
            checkTimezone(settings, snapshot, violation);
            checked.add("mysql-pool-budget");
            checkPoolBudget(settings, snapshot, violation);
        }

        if (settings.xxlEnabled()) {
            checked.add("xxl-job");
            checkXxl(settings, violation);
        }

        return new Report(checked, warnings, errors);
    }

    private static void validateSettings(Settings s, Consumer<String> violation) {
        if (s.expectedInstances() < 1) {
            violation.accept("coupon.runtime.expected-instances 必须 >= 1，实际=" + s.expectedInstances());
        }
        if (s.instancesPerHost() < 1) {
            violation.accept("coupon.runtime.instances-per-host 必须 >= 1，实际=" + s.instancesPerHost());
        }
        if (s.businessPoolMax() < 1) {
            violation.accept("coupon.sharding.pool.max-size 必须 >= 1，实际=" + s.businessPoolMax());
        }
        if (s.rawBulkPoolMax() < 1) {
            violation.accept("coupon.issue.worker.raw-bulk-pool-size 必须 >= 1，实际=" + s.rawBulkPoolMax());
        }
        if (s.safetyConnections() < 0) {
            violation.accept("coupon.runtime.mysql-safety-connections 不能为负，实际=" + s.safetyConnections());
        }
        if (s.maxUtilization() <= 0 || s.maxUtilization() > 1) {
            violation.accept("coupon.runtime.mysql-max-utilization 必须在 (0,1]，实际=" + s.maxUtilization());
        }
    }

    private static void checkTimezone(Settings s, DbSnapshot db, Consumer<String> violation) {
        Instant now = Instant.now();
        long jvmOffset = s.jvmZone().getRules().getOffset(now).getTotalSeconds();
        long billOffset = s.billZone().getRules().getOffset(now).getTotalSeconds();
        long dbOffset = db.sessionOffsetSeconds();

        boolean jvmMatchesDb = Math.abs(jvmOffset - dbOffset) <= OFFSET_TOLERANCE_SECONDS;
        boolean billMatchesDb = Math.abs(billOffset - dbOffset) <= OFFSET_TOLERANCE_SECONDS;
        boolean jvmMatchesBill = Math.abs(jvmOffset - billOffset) <= OFFSET_TOLERANCE_SECONDS;
        if (jvmMatchesDb && billMatchesDb && jvmMatchesBill) {
            return;
        }
        violation.accept(String.format(
                "时区不一致：JVM=%s(offset %+d s)，账单日=%s(offset %+d s)，"
                        + "MySQL session=%s/global=%s/system=%s(offset %+d s)；"
                        + "SQL 的 NOW() 与 JVM LocalDateTime.now() 会按不同墙钟比较，"
                        + "锁定超时/过期/stat_day/账单日可能错位",
                s.jvmZone(), jvmOffset, s.billZone(), billOffset,
                db.sessionTimeZone(), db.globalTimeZone(), db.systemTimeZone(), dbOffset));
    }

    private static void checkPoolBudget(Settings s, DbSnapshot db, Consumer<String> violation) {
        long required = (long) s.expectedInstances()
                * ((long) s.businessPoolMax() + s.rawBulkPoolMax())
                + s.safetyConnections();
        long allowed = (long) Math.floor(db.maxConnections() * s.maxUtilization());
        if (required > allowed) {
            violation.accept(String.format(
                    "MySQL 连接数预算不足：%d 实例 ×（业务池 %d + 裸写池 %d）+ 安全余量 %d = %d，"
                            + "超过上限 %d × %.2f = %d（max_connections=%d）；"
                            + "多实例上线前必须调低池大小或抬高 max_connections",
                    s.expectedInstances(), s.businessPoolMax(), s.rawBulkPoolMax(),
                    s.safetyConnections(), required, db.maxConnections(), s.maxUtilization(),
                    allowed, db.maxConnections()));
        }
    }

    private static void checkXxl(Settings s, Consumer<String> violation) {
        if (s.xxlPort() < 1 || s.xxlPort() > 65_535) {
            violation.accept("xxl.job.port 非法：" + s.xxlPort());
        }
        if (isBlank(s.xxlAccessToken())) {
            violation.accept("xxl.job.accessToken 为空：执行器无法注册到调度中心");
        } else if (DEFAULT_XXL_TOKEN.equals(s.xxlAccessToken())) {
            violation.accept("xxl.job.accessToken 仍是默认值 default_token：生产必须用 XXL_JOB_ACCESS_TOKEN 覆盖");
        }
        if (isBlank(s.xxlAppname())) {
            violation.accept("xxl.job.appname 为空：执行器无法注册到调度中心");
        } else if (DEFAULT_XXL_APPNAME.equals(s.xxlAppname())) {
            violation.accept("xxl.job.appname 仍是默认值 newcoupon-executor："
                    + "多环境/多集群共用会让调度中心把任务路由到错误实例");
        }

        Path logPath = null;
        if (isBlank(s.xxlLogPath())) {
            violation.accept("xxl.job.logPath 为空：执行器无法写任务日志");
        } else {
            try {
                logPath = Path.of(s.xxlLogPath());
                if (!logPath.isAbsolute()) {
                    violation.accept("xxl.job.logPath 必须是绝对路径：" + s.xxlLogPath());
                    logPath = null;
                } else {
                    Files.createDirectories(logPath);
                    if (!Files.isWritable(logPath)) {
                        violation.accept("xxl.job.logPath 不可写：" + s.xxlLogPath());
                    }
                }
            } catch (IOException | RuntimeException e) {
                violation.accept("xxl.job.logPath 不可用（" + s.xxlLogPath() + "）：" + e);
                logPath = null;
            }
        }

        if (s.instancesPerHost() > 1) {
            if (s.xxlPort() == DEFAULT_XXL_PORT) {
                violation.accept("同机部署 " + s.instancesPerHost()
                        + " 个实例时 xxl.job.port 不能保留默认 9999，必须按实例用 XXL_JOB_PORT 覆盖");
            }
            if (logPath != null && !s.xxlLogPath().contains(String.valueOf(s.xxlPort()))) {
                violation.accept("同机多实例的 xxl.job.logPath 必须包含端口（当前 port="
                        + s.xxlPort() + "，path=" + s.xxlLogPath() + "），避免任务日志混写");
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ------------------------------------------------------------------
    // 数据载体（public：集成测试可读报告；Settings/DbSnapshot 便于纯单测）
    // ------------------------------------------------------------------

    public record Settings(
            boolean strict,
            int expectedInstances,
            int instancesPerHost,
            int businessPoolMax,
            int rawBulkPoolMax,
            int safetyConnections,
            double maxUtilization,
            boolean xxlEnabled,
            String xxlAppname,
            int xxlPort,
            String xxlAccessToken,
            String xxlLogPath,
            ZoneId jvmZone,
            ZoneId billZone) {
    }

    public record DbSnapshot(
            String sessionTimeZone,
            String globalTimeZone,
            String systemTimeZone,
            long sessionOffsetSeconds,
            int maxConnections) {
    }

    public record Report(List<String> checked, List<String> warnings, List<String> errors) {

        public Report {
            checked = List.copyOf(checked);
            warnings = List.copyOf(warnings);
            errors = List.copyOf(errors);
        }

        static Report empty() {
            return new Report(List.of(), List.of(), List.of());
        }

        public boolean healthy() {
            return errors.isEmpty();
        }
    }
}
