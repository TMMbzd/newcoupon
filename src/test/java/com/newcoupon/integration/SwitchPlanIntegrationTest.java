package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.PlanApplyService;
import com.newcoupon.application.service.SwitchPlan;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.common.BizException;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppConfigLogMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppConfigLogPO;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 三级预案的套用与恢复（S2 第 10 步）——<b>红绿灯</b>。
 *
 * <p>钉住四条契约（设计文档 §5）：
 * <ol>
 *   <li><b>全量物化</b>：三份预案声明同一组键——"先红后黄"必须与"直接套黄"结果相同。
 *       否则套用留下前一份预案的残影，而运维以为已经切过去了；</li>
 *   <li><b>幂等</b>：重复套用状态不变、审计不增行（预案是模板化操作，会被重试和演练）；</li>
 *   <li><b>快照补偿回滚</b>：恢复把每个键还成套用前的样子（含"原本没有这一项"）；</li>
 *   <li><b>CAS 冲突</b>：期间被人工改过的键跳过回滚、报告冲突——**人的意图不该被机器覆盖**。</li>
 * </ol>
 *
 * <p>每个用例**自建独有租户**（铁律①：用例的数据自己建，不共享）——
 * 预案会写一批配置，共享租户必然互相污染。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.admin.token=test-admin-token"
        })
class SwitchPlanIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String OPERATOR = "test-plan@local";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private OpenAppService appService;

    @Autowired
    private PlanApplyService planService;

    @Autowired
    private TenantConfigService configService;

    @Autowired
    private OpenAppConfigLogMapper logMapper;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate rest;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String appId;

    @BeforeEach
    void newTenant() {
        appId = "t-plan-" + SEQ.incrementAndGet() + "-" + (System.nanoTime() % 100_000);
        appService.create(new CreateAppRequest(appId, "预案测试", null, null, null));
    }

    private String newTenant(String label) {
        String id = "t-plan-" + label + "-" + SEQ.incrementAndGet() + "-" + (System.nanoTime() % 100_000);
        appService.create(new CreateAppRequest(id, "预案测试", null, null, null));
        return id;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("P1 套用黄：非核心关闭、核心保持，且每一项都有确定值（全量物化）")
    void p1_applyYellow() {
        var result = planService.apply(appId, SwitchPlan.YELLOW, OPERATOR, "test");

        assertThat(result.changedKeys()).as("首次套用应当有键发生变化").isPositive();
        assertThat(configService.getRaw(appId, "switch.template.publish.enabled"))
                .as("模板发布是非核心 → 关").isEqualTo("false");
        assertThat(configService.getRaw(appId, "switch.receive.enabled"))
                .as("领券是核心 → 必须保持开").isEqualTo("true");
        assertThat(configService.getRaw(appId, "switch.trade.confirm.enabled"))
                .as("核销是核心 → 必须保持开").isEqualTo("true");
        // 预案里黄没关、但**声明了**的键也要被写成确定值——这正是"全量物化"的含义
        assertThat(configService.getRaw(appId, "rate_limit.warmup_seconds")).isEqualTo("0");
    }

    @Test
    @DisplayName("P2 无残影：先红后黄 与 直接套黄 结果逐键相同（全量物化的意义）")
    void p2_noResidueBetweenPlans() {
        String viaRed = newTenant("viared");
        String direct = newTenant("direct");

        planService.apply(viaRed, SwitchPlan.RED, OPERATOR, "test");
        planService.apply(viaRed, SwitchPlan.YELLOW, OPERATOR, "test");   // 红 → 黄

        planService.apply(direct, SwitchPlan.YELLOW, OPERATOR, "test");   // 直接黄

        for (SwitchPlan.PlanEntry entry : SwitchPlan.YELLOW.entries()) {
            assertThat(configService.getRaw(viaRed, entry.configKey()))
                    .as("键 %s 在两租户间应当一致——若不同，说明切换预案留下了上一份的残影"
                            + "（例如红的限流值没被黄覆盖掉）", entry.configKey())
                    .isEqualTo(configService.getRaw(direct, entry.configKey()));
        }
    }

    @Test
    @DisplayName("P3 幂等：重复套用状态不变、且不产生审计噪音")
    void p3_repeatedApplyIsIdempotent() {
        planService.apply(appId, SwitchPlan.YELLOW, OPERATOR, "test");
        long logsBefore = countLogs("switch.template.publish.enabled");

        var second = planService.apply(appId, SwitchPlan.YELLOW, OPERATOR, "test");

        assertThat(second.changedKeys())
                .as("第二次套用，所有键都已经是目标值 → 变化数必须是 0").isZero();
        assertThat(countLogs("switch.template.publish.enabled"))
                .as("值没变就不该写审计——否则演练和重试会把真正的人工改动淹没").isEqualTo(logsBefore);
    }

    @Test
    @DisplayName("P4 快照回滚：恢复后回到套用前的样子——**包括运维原本手工配过的值**")
    void p4_revertRestoresSnapshot() {
        // 运维此前手工把限流配成 777（预案要能把它还回来，而不是抹成"没有"）
        configService.put(appId, Map.of("rate_limit.qps", "777"), "ops@local");

        planService.apply(appId, SwitchPlan.RED, OPERATOR, "test");
        assertThat(configService.getRaw(appId, "rate_limit.qps")).isEqualTo("20");

        var reverted = planService.revert(appId, OPERATOR, "test");

        assertThat(reverted.conflicts()).as("期间没人改过，不该有冲突").isEmpty();
        assertThat(configService.getRaw(appId, "rate_limit.qps"))
                .as("恢复必须把 777 还回来，而不是删掉回落默认——这正是快照补偿与"
                        + "「无条件删除」的区别").isEqualTo("777");
        assertThat(configService.getRaw(appId, "switch.template.publish.enabled"))
                .as("黄/红期间被关掉的开关，恢复后应当回落（原本没有这一项 → 删掉）").isNull();
    }

    @Test
    @DisplayName("P5 CAS 冲突：期间被人工改过的键跳过回滚并报告——人的意图不该被机器覆盖")
    void p5_revertSkipsManuallyChangedKeys() {
        planService.apply(appId, SwitchPlan.YELLOW, OPERATOR, "test");
        // 套用之后，有人手工把领券关了（例如运维现场处置）
        configService.put(appId, Map.of("switch.receive.enabled", "false"), "ops@local");

        var reverted = planService.revert(appId, OPERATOR, "test");

        assertThat(reverted.conflicts())
                .as("被人工改过的键必须报出来，让人知道「恢复没有把它还原」")
                .contains("switch.receive.enabled");
        assertThat(configService.getRaw(appId, "switch.receive.enabled"))
                .as("人的意图不能被机器静默覆盖").isEqualTo("false");
        assertThat(configService.getRaw(appId, "switch.template.publish.enabled"))
                .as("没被改过的键照常回滚").isNull();
    }

    @Test
    @DisplayName("P6 没有生效中的预案时恢复 → 明确报错（不是静默成功）")
    void p6_revertWithoutActivePlanFails() {
        assertThatThrownBy(() -> planService.revert(appId, OPERATOR, "test"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有生效中的预案");
    }

    @Test
    @DisplayName("P7 未知预案级别 → 报错并列出可选值")
    void p7_unknownLevelRejected() {
        assertThatThrownBy(() -> SwitchPlan.fromLevel("PURPLE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("BLUE").hasMessageContaining("YELLOW").hasMessageContaining("RED");
    }

    // ------------------------------------------------------------------
    // 管理面（HTTP 层）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H1 无令牌套用预案 → 401 + 7002（预案写接口必须受管理面令牌保护）")
    void h1_withoutTokenRejected() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        var r = rest.exchange("/api/apps/" + appId + "/plans/apply/YELLOW",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("{}", headers), String.class);

        assertThat(r.getStatusCode().value())
                .as("一个无鉴权的「一键降级」接口 = 暴露在网络上的 kill-switch："
                        + "能把核心领券/核销全量打成 5001，也能把别人刚关掉的开关改回去")
                .isEqualTo(401);
        assertThat(r.getBody()).contains("7002");
    }

    @Test
    @DisplayName("H2 带令牌套用 → 200，且配置真的被改了（不是「接口成功但什么都没写」）")
    void h2_withTokenAppliesPlan() {
        var r = rest.exchange("/api/apps/" + appId + "/plans/apply/YELLOW",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("{\"reason\":\"drill\"}", adminHeaders()),
                String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":0");
        assertThat(configService.getRaw(appId, "switch.template.publish.enabled"))
                .as("黄预案要关掉模板发布——接口返回成功但配置没变是必须防住的假绿")
                .isEqualTo("false");
    }

    @Test
    @DisplayName("H3 读取接口：当前预案 + 可选清单（运维要能看见「有哪些预案、各自会改什么」）")
    void h3_listShowsActiveAndAvailable() throws Exception {
        planService.apply(appId, SwitchPlan.YELLOW, OPERATOR, "test");

        var r = rest.getForEntity("/api/apps/" + appId + "/plans", String.class);
        var data = objectMapper.readTree(r.getBody()).path("data");

        assertThat(data.path("active").path("level").asText()).isEqualTo("YELLOW");
        assertThat(data.path("available"))
                .as("三份预案都要列出来，且带着「会改哪些键」——出事时人不用翻代码")
                .hasSize(3);
    }

    @Test
    @DisplayName("H4 无令牌恢复 → 401（恢复同样是写操作，不能比套用松）")
    void h4_revertWithoutTokenRejected() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        var r = rest.exchange("/api/apps/" + appId + "/plans/revert",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("{}", headers), String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------------

    private static org.springframework.http.HttpHeaders adminHeaders() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", "test-admin-token");
        return headers;
    }

    private long countLogs(String configKey) {
        return logMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpenAppConfigLogPO>()
                        .eq("app_id", appId).eq("config_key", configKey));
    }
}
