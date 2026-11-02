package com.newcoupon.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.config.RuntimeConfigValidator.DbSnapshot;
import com.newcoupon.config.RuntimeConfigValidator.Report;
import com.newcoupon.config.RuntimeConfigValidator.Settings;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 运行时配置校验的单元合同（不启动 Spring/容器）——三条部署事故的提前暴露：
 *
 * <ol>
 *   <li><b>时区错位</b>：JVM / 账单日 / MySQL session 三者偏移必须一致；
 *       SQL {@code NOW()} 与 JVM {@code LocalDateTime.now()} 混用时差 8 小时会打穿锁超时与过期。</li>
 *   <li><b>连接数预算</b>：多实例 ×（业务池 + 裸写池）+ 安全余量不能超过
 *       {@code max_connections × utilization}。</li>
 *   <li><b>XXL 默认值</b>：默认 token/appname、同机多实例共用端口/日志目录。</li>
 * </ol>
 *
 * <p>严格度：strict=false（dev）只进 warnings；strict=true（prod）进 errors 并阻止启动。
 */
class RuntimeConfigValidatorTest {

    private static final long CST_OFFSET_SECONDS = 28_800L;

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 时区
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全部一致 + strict：零告警零错误")
    void allChecksPass_strictMode_noWarningsOrErrors() {
        Report report = RuntimeConfigValidator.evaluate(
                new SettingsBuilder().strict(true).build(), db(CST_OFFSET_SECONDS, 151), null);

        assertThat(report.healthy()).isTrue();
        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(report.checked()).contains("timezone", "mysql-pool-budget");
    }

    @Test
    @DisplayName("时区不一致：dev 只告警，不阻止启动")
    void timezoneMismatch_warnsInDev() {
        Report report = RuntimeConfigValidator.evaluate(
                new SettingsBuilder().build(), db(0L, 151), null);

        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).anyMatch(w -> w.contains("时区不一致"));
        assertThat(report.checked()).contains("timezone");
    }

    @Test
    @DisplayName("时区不一致：prod(strict) 进 errors，启动失败")
    void timezoneMismatch_failsInProd() {
        Report report = RuntimeConfigValidator.evaluate(
                new SettingsBuilder().strict(true).build(), db(0L, 151), null);

        assertThat(report.errors()).anyMatch(e -> e.contains("时区不一致"));
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    @DisplayName("JVM 与 DB 一致但账单日时区不同：strict 必须失败")
    void jvmMatchesDbButBillZoneDiffers_fails() {
        Settings settings = new SettingsBuilder()
                .strict(true)
                .jvmZone(ZoneId.of("UTC"))
                .billZone(RuntimeConfigValidator.BILL_ZONE)
                .build();

        Report report = RuntimeConfigValidator.evaluate(settings, db(0L, 151), null);

        assertThat(report.errors()).anyMatch(e -> e.contains("账单日"));
    }

    // ------------------------------------------------------------------
    // 连接数预算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("预算按实例数叠加业务池与裸写池；恰好不超时通过，超出即告警")
    void poolBudget_countsInstancesAndRawBulk() {
        // 2 × (20 + 4) + 20 = 68；100 × 0.8 = 80 → 通过
        Settings fits = new SettingsBuilder().expectedInstances(2).build();
        Report pass = RuntimeConfigValidator.evaluate(fits, db(CST_OFFSET_SECONDS, 100), null);
        assertThat(pass.errors()).isEmpty();
        assertThat(pass.warnings()).isEmpty();

        // 84 × 0.8 = 67 < 68 → 差 1 条也必须是问题（dev=WARN）
        Report over = RuntimeConfigValidator.evaluate(fits, db(CST_OFFSET_SECONDS, 84), null);
        assertThat(over.warnings()).anyMatch(w -> w.contains("连接数预算"));
    }

    @Test
    @DisplayName("连接数预算不足：prod(strict) 进 errors")
    void poolBudget_overMaxConnections_failsInProd() {
        Settings settings = new SettingsBuilder().strict(true).expectedInstances(2).build();

        Report report = RuntimeConfigValidator.evaluate(settings, db(CST_OFFSET_SECONDS, 70), null);

        assertThat(report.errors()).anyMatch(e -> e.contains("连接数预算"));
    }

    // ------------------------------------------------------------------
    // XXL-JOB
    // ------------------------------------------------------------------

    @Test
    @DisplayName("XXL 默认 token/appname：dev 告警，prod 失败")
    void xxlDefaults_warnInDevAndFailInProd() {
        String logPath = tempDir.resolve("xxl").toString();
        Settings dev = new SettingsBuilder().xxlEnabled(true).xxlLogPath(logPath).build();
        Report devReport = RuntimeConfigValidator.evaluate(dev, db(CST_OFFSET_SECONDS, 151), null);
        assertThat(devReport.errors()).isEmpty();
        assertThat(devReport.warnings()).anyMatch(w -> w.contains("default_token"));
        assertThat(devReport.warnings()).anyMatch(w -> w.contains("newcoupon-executor"));

        Settings prod = new SettingsBuilder()
                .strict(true).xxlEnabled(true).xxlLogPath(logPath).build();
        Report prodReport = RuntimeConfigValidator.evaluate(prod, db(CST_OFFSET_SECONDS, 151), null);
        assertThat(prodReport.errors()).anyMatch(e -> e.contains("default_token"));
        assertThat(prodReport.errors()).anyMatch(e -> e.contains("newcoupon-executor"));
    }

    @Test
    @DisplayName("XXL 关闭：不检查 token/appname/端口，不影响本地开发")
    void xxlDisabled_skipsXxlChecks() {
        Report report = RuntimeConfigValidator.evaluate(
                new SettingsBuilder().xxlEnabled(false).build(), db(CST_OFFSET_SECONDS, 151), null);

        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(report.checked()).doesNotContain("xxl-job");
    }

    @Test
    @DisplayName("同机多实例：保留默认端口且日志目录不含端口 → strict 失败")
    void xxlMultiInstanceSamePortAndLogPath_fails() {
        Settings settings = new SettingsBuilder()
                .strict(true)
                .instancesPerHost(2)
                .xxlEnabled(true)
                .xxlLogPath(tempDir.resolve("logs").toString())
                .build();

        Report report = RuntimeConfigValidator.evaluate(settings, db(CST_OFFSET_SECONDS, 151), null);

        assertThat(report.errors()).anyMatch(e -> e.contains("9999"));
        assertThat(report.errors()).anyMatch(e -> e.contains("logPath"));
    }

    // ------------------------------------------------------------------
    // 探测失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("数据库探测失败：dev 告警，prod 失败")
    void probeFailure_warnsInDevAndFailsInProd() {
        Report dev = RuntimeConfigValidator.evaluate(new SettingsBuilder().build(), null, "boom");
        assertThat(dev.errors()).isEmpty();
        assertThat(dev.warnings()).anyMatch(w -> w.contains("数据库配置探测失败"));

        Report prod = RuntimeConfigValidator.evaluate(
                new SettingsBuilder().strict(true).build(), null, "boom");
        assertThat(prod.errors()).anyMatch(e -> e.contains("数据库配置探测失败"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static DbSnapshot db(long offsetSeconds, int maxConnections) {
        return new DbSnapshot("SYSTEM", "SYSTEM", "CST", offsetSeconds, maxConnections);
    }

    /** 测试专用可变构造器：默认值 = application.yml 的公共默认（dev 单实例、XXL 关闭）。 */
    private static final class SettingsBuilder {
        private boolean strict;
        private int expectedInstances = 1;
        private int instancesPerHost = 1;
        private int businessPoolMax = 20;
        private int rawBulkPoolMax = 4;
        private int safetyConnections = 20;
        private double maxUtilization = 0.8;
        private boolean xxlEnabled;
        private String xxlAppname = RuntimeConfigValidator.DEFAULT_XXL_APPNAME;
        private int xxlPort = RuntimeConfigValidator.DEFAULT_XXL_PORT;
        private String xxlAccessToken = RuntimeConfigValidator.DEFAULT_XXL_TOKEN;
        private String xxlLogPath = "/tmp/xxl-job/log";
        private ZoneId jvmZone = RuntimeConfigValidator.BILL_ZONE;
        private ZoneId billZone = RuntimeConfigValidator.BILL_ZONE;

        SettingsBuilder strict(boolean value) {
            this.strict = value;
            return this;
        }

        SettingsBuilder expectedInstances(int value) {
            this.expectedInstances = value;
            return this;
        }

        SettingsBuilder instancesPerHost(int value) {
            this.instancesPerHost = value;
            return this;
        }

        SettingsBuilder xxlEnabled(boolean value) {
            this.xxlEnabled = value;
            return this;
        }

        SettingsBuilder xxlLogPath(String value) {
            this.xxlLogPath = value;
            return this;
        }

        SettingsBuilder jvmZone(ZoneId value) {
            this.jvmZone = value;
            return this;
        }

        SettingsBuilder billZone(ZoneId value) {
            this.billZone = value;
            return this;
        }

        Settings build() {
            return new Settings(strict, expectedInstances, instancesPerHost,
                    businessPoolMax, rawBulkPoolMax, safetyConnections, maxUtilization,
                    xxlEnabled, xxlAppname, xxlPort, xxlAccessToken, xxlLogPath,
                    jvmZone, billZone);
        }
    }
}
