package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.infrastructure.ratelimit.TenantFlowRuleDataSource;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 第 3 步验收：**规则真的从数据库来**（2026-10-08）。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>租户登记表（{@code open_app.qps_limit}）里的配额，能被翻译成 Sentinel 规则并生效；</li>
 *   <li>租户配置（{@code open_app_config.rate_limit.qps}）**覆盖**登记表——这是本项目的
 *       "配置优先、未配置回落登记表"约定；</li>
 *   <li>停用的租户**不发规则**（它们连认证都过不了，发规则只是白白增加重建成本）。</li>
 * </ol>
 *
 * <p><b>为什么断言"包含"而不是"等于"</b>：数据源是全量重建的，而 {@code FlowRuleManager}
 * 是 **JVM 级静态状态**——别的测试类建的租户也会在里面。所以只能断言"我们这个租户的规则在"，
 * 不能断言"只有我们的"。这也是共享测试容器之后更要紧的一条纪律（见工程手记 2.2b）。
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="
})
class SentinelRuleSourceTest extends ShardedIntegrationTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private OpenAppService appService;

    @Autowired
    private TenantConfigService tenantConfigService;

    @Autowired
    private TenantFlowRuleDataSource dataSource;

    private String newTenant(int qpsLimit, int dailyQuota) {
        String appId = "t-rulesrc-" + SEQ.incrementAndGet() + "-" + (System.nanoTime() % 100_000);
        appService.create(new CreateAppRequest(appId, "规则源验证", qpsLimit, dailyQuota, null));
        return appId;
    }

    /**
     * 读 FlowRuleManager 当前的规则。
     *
     * <p>用反射调 {@code getRules()}（字节码里确认存在的方法），而不是直接读私有字段——
     * 第一版读字段读出来恒为空，容易被误判成"规则没进去"。
     */
    @SuppressWarnings("unchecked")
    private static List<FlowRule> currentRules() throws Exception {
        Class<?> cls = Class.forName("com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager");
        for (String name : new String[]{"getRules", "getFlowRules"}) {
            try {
                var m = cls.getMethod(name);
                Object v = m.invoke(null);
                if (v instanceof List<?> list) {
                    return (List<FlowRule>) list;
                }
            } catch (NoSuchMethodException ignored) {
                // 试下一个名字
            }
        }
        return List.of();
    }

    private static FlowRule ruleFor(List<FlowRule> rules, String appId) {
        return rules.stream()
                .filter(r -> appId.equals(r.getLimitApp()))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("登记表的配额被翻译成规则：新建租户 → 刷新 → 规则里出现该租户")
    void registeredQuotaBecomesRule() throws Exception {
        String appId = newTenant(50, 0);

        dataSource.refreshNow();

        FlowRule rule = ruleFor(currentRules(), appId);
        List<FlowRule> all = currentRules();
        // 同时看数据源自己读到了什么——区分"数据源读不到"与"规则没进 FlowRuleManager"
        var quotas = dataSource.readSource();
        assertThat(rule)
                .as("新建的租户 " + appId + " 应当出现在规则里。"
                        + "当前规则总数=" + all.size()
                        + "，limitApp 列表=" + all.stream().map(FlowRule::getLimitApp).toList()
                        + "；数据源读到 " + quotas.size() + " 个租户配额，"
                        + "其中含本租户=" + quotas.stream().anyMatch(q -> appId.equals(q.appId())))
                .isNotNull();
        assertThat(rule.getResource()).isEqualTo("open_api");
        assertThat(rule.getCount()).isEqualTo(50);
    }

    @Test
    @DisplayName("租户配置覆盖登记表：配了 rate_limit.qps=200 → 规则用 200 而不是登记表的 50")
    void tenantConfigOverridesRegisteredQuota() throws Exception {
        String appId = newTenant(50, 0);
        tenantConfigService.put(appId, Map.of("rate_limit.qps", "200"), "test");

        dataSource.refreshNow();

        FlowRule rule = ruleFor(currentRules(), appId);
        assertThat(rule).isNotNull();
        assertThat(rule.getCount())
                .as("租户配置优先——这是本项目「配置优先、未配置回落登记表」的约定")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("qpsLimit=0（不限）的租户**不发规则**——不是发一条 count=0 的（那是全拒）")
    void unlimitedTenantHasNoRule() throws Exception {
        String appId = newTenant(0, 0);

        dataSource.refreshNow();

        assertThat(ruleFor(currentRules(), appId))
                .as("0 = 不限 → 不该有规则。若这里出现一条 count=0 的规则，那个租户会被**全部拒绝**——"
                        + "两个语义正好相反，是本次迁移最容易搞错的一处")
                .isNull();
    }

    @Test
    @DisplayName("租户配置把配额改成 0 → 规则消失（从不限到限、从限到不限，两个方向都要能收敛）")
    void turningQuotaOffRemovesRule() throws Exception {
        String appId = newTenant(50, 0);
        dataSource.refreshNow();
        assertThat(ruleFor(currentRules(), appId)).as("先确认规则在").isNotNull();

        tenantConfigService.put(appId, Map.of("rate_limit.qps", "0"), "test");
        dataSource.refreshNow();

        assertThat(ruleFor(currentRules(), appId))
                .as("改成不限之后规则应当消失。如果它还在（count=0），那个租户会被全拒")
                .isNull();
    }

    @Test
    @DisplayName("租户配置优先于登记表：这个键是 RATE_LIMIT_QPS（与过滤器的 resolveLimit 同一套词汇）")
    void usesTheSameConfigKeyAsBefore() {
        assertThat(TenantConfigKey.RATE_LIMIT_QPS.key())
                .as("键名必须与迁移前一致，否则运维按老文档配了却不生效")
                .isEqualTo("rate_limit.qps");
    }
}
