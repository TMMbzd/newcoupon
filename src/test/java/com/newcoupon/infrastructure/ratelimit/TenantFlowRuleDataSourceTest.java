package com.newcoupon.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.domain.open.OpenApp;
import com.newcoupon.domain.repository.OpenAppRepository;
import com.newcoupon.infrastructure.metrics.GovernanceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规则源：**"读到空"绝不能变成"清空规则"**（迁移第 7 步，2026-10-08）。
 *
 * <p><b>红</b>：Sentinel 的 {@code AutoRefreshDataSource} 只在 {@code loadConfig()}
 * <b>抛异常</b>时保留旧规则（fail-static）；**返回空列表或 null 会一路推到
 * {@code FlowRuleManager} 把规则整表清空**（源码级核实，另在 1.8.10 jar 上实跑过）。
 * 而我们的 {@code readSource()} 在"登记表一行都读不到"时恰好返回空列表、且不抛异常——
 * 于是**所有租户静默变成不限流**，应用日志里只有一行 debug。
 *
 * <p>这不是理论风险：把"空的租户行"和"空的规则"混为一谈，是这套机制里唯一一处
 * **错一下就是全体失守**的路径。所以宁可"读到 0 行就报错、保留旧规则"。
 *
 * <p><b>刻意不拦的情况</b>：租户行存在、但配额都是 0（= 不限）——那是**合法的空**，
 * 属于运维的正常操作，拦它会让"把限流全关掉"这个操作被冻住（"以为关了其实没关"比不关更糟）。
 */
class TenantFlowRuleDataSourceTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GovernanceMetrics metrics = new GovernanceMetrics(meterRegistry);

    private TenantFlowRuleDataSource dataSource;

    @AfterEach
    void closeTimer() throws Exception {
        if (dataSource != null) {
            dataSource.close();   // 父类会起一个定时刷新线程，用例结束要关掉
        }
    }

    private TenantFlowRuleDataSource dataSource(List<OpenApp> apps) {
        OpenAppRepository repository = mock(OpenAppRepository.class);
        when(repository.findAllApps()).thenReturn(apps);
        TenantConfigService config = mock(TenantConfigService.class);
        when(config.get(anyString(), any())).thenReturn(null);   // 未配置 → 回落登记表
        // 刷新周期给得很大：用例只手动调 readSource()，不要让定时线程插进来
        dataSource = new TenantFlowRuleDataSource(repository, config, 3_600_000L, metrics);
        return dataSource;
    }

    private static OpenApp app(String appId, int status, int qps) {
        return new OpenApp(appId, "测试租户", status, qps, 0, null, null);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("登记表一行都读不到 → **抛异常保旧规则**，并打 fail-open 指标（不能清空规则）")
    void emptyRegistry_throwsInsteadOfClearingRules() {
        TenantFlowRuleDataSource ds = dataSource(List.of());

        assertThatThrownBy(ds::readSource)
                .as("返回空列表会被 Sentinel 当成「规则就该是空的」→ 整表清空 → 所有租户不限流。"
                        + "抛异常才会走它的 fail-static 分支：保留上一轮的规则")
                .isInstanceOf(RuntimeException.class);

        assertThat(meterRegistry.find("coupon_ratelimit_failopen_total")
                .tag("dimension", "rule_source").counter())
                .as("**必须可见**：规则源读空导致防线失效，与「流量本来就小」在监控上必须能区分")
                .isNotNull();
    }

    @Test
    @DisplayName("正常读到租户 → 翻译成配额，不打 fail-open")
    void normalRead_mapsQuotas() {
        TenantFlowRuleDataSource ds = dataSource(List.of(
                app("t-rulesrc-a", OpenApp.STATUS_ENABLED, 50),
                app("t-rulesrc-b", OpenApp.STATUS_ENABLED, 0)));

        assertThatCode(ds::readSource).doesNotThrowAnyException();
        assertThat(ds.readSource())
                .as("两个启用租户都进来（qps=0 的那条会在翻译阶段被丢掉，不是在这里）")
                .hasSize(2);

        assertThat(meterRegistry.find("coupon_ratelimit_failopen_total")
                .tag("dimension", "rule_source").counter())
                .as("正常读到就不该打 fail-open，否则这个指标会变成噪音").isNull();
    }

    @Test
    @DisplayName("有租户行但**全部停用** → 按合法空推送（不拦：那是运维的正常操作）")
    void allDisabled_isALegalEmpty() {
        TenantFlowRuleDataSource ds = dataSource(List.of(
                app("t-rulesrc-off", OpenApp.STATUS_DISABLED, 50)));

        assertThatCode(ds::readSource)
                .as("全部停用意味着所有调用方都过不了验签，规则有没有都一样。"
                        + "拦它会把「紧急停掉所有接入方」这个操作冻住——那比不拦更糟")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("有租户但配额全为 0（= 不限）→ 合法空，**不许拦**")
    void allUnlimited_isALegalEmpty() {
        TenantFlowRuleDataSource ds = dataSource(List.of(
                app("t-rulesrc-unlimited", OpenApp.STATUS_ENABLED, 0)));

        assertThatCode(ds::readSource).doesNotThrowAnyException();
        assertThat(ds.readSource()).as("行还在，只是配额不限").isNotEmpty();
    }
}
