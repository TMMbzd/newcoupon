package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.TenantConfigKey;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.infrastructure.persistence.mapper.OpenAppConfigMapper;
import com.newcoupon.infrastructure.persistence.po.OpenAppConfigPO;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S2 第 1 步：租户配置的存储与读取（RQ-1）——<b>红绿灯</b>。
 *
 * <p>红：本类先于实现写就——读取服务的方法体抛"待实现"，四条用例全红。
 *
 * <p>钉住三条不可让步的规则（设计文档 §4.1）：
 * <ol>
 *   <li>没配 → 用默认值（不让"配置缺失"变成"功能不可用"）；</li>
 *   <li>配错 → 也用默认值 + 记 WARN，且**请求不失败**；</li>
 *   <li>按租户隔离：一个租户的配置不影响另一个。</li>
 * </ol>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.admin.token=test-admin-token"     // 管理面 HTTP 用例要过令牌过滤器
        })
class TenantConfigIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String APP_A = "t-cfg-a";
    private static final String APP_B = "t-cfg-b";
    /** 专供"完全未配置"的断言用：共享租户会被别的用例写脏，"整体为空"这种断言必须有自己的数据 */
    private static final String APP_CLEAN = "t-cfg-clean";

    @Autowired
    private TenantConfigService configService;

    @Autowired
    private OpenAppConfigMapper configMapper;

    @Autowired
    private OpenAppService appService;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate rest;

    @Autowired
    private com.newcoupon.infrastructure.persistence.mapper.OpenAppConfigLogMapper logMapper;

    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        appService.create(new CreateAppRequest(APP_A, "配置测试甲", null, null, null));
        appService.create(new CreateAppRequest(APP_B, "配置测试乙", null, null, null));
        appService.create(new CreateAppRequest(APP_CLEAN, "从未配置过", null, null, null));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("没配过 → 用该键的默认值（配置缺失不该变成功能不可用）")
    void unconfigured_fallsBackToDefault() {
        assertThat(configService.get(APP_CLEAN, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS))
                .isEqualTo("30");
        assertThat(configService.getInt(APP_CLEAN, TenantConfigKey.TIMEOUT_DEFAULT_MS)).isEqualTo(3000);
        assertThat(configService.getInt(APP_CLEAN, TenantConfigKey.CIRCUIT_BREAKER_FAILURE_RATE)).isEqualTo(50);
        assertThat(configService.allOf(APP_CLEAN)).as("这个租户没有任何显式配置").isEmpty();
    }

    @Test
    @DisplayName("配过了 → 用配置值（管理面写入后要能被读到）")
    void configured_usesStoredValue() {
        put(APP_A, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS, "45");
        put(APP_A, TenantConfigKey.TIMEOUT_DEFAULT_MS, "800");
        configService.invalidate(APP_A);   // 管理面改配置后的主动失效

        assertThat(configService.get(APP_A, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS)).isEqualTo("45");
        assertThat(configService.getInt(APP_A, TenantConfigKey.TIMEOUT_DEFAULT_MS)).isEqualTo(800);
        assertThat(configService.allOf(APP_A)).containsEntry("timeout.default_ms", "800");
    }

    @Test
    @DisplayName("配错了 → 回落默认值、**不抛异常**（配置错误是运维问题，不该变成用户的请求失败）")
    void malformedValue_fallsBackToDefaultWithoutThrowing() {
        put(APP_A, TenantConfigKey.TIMEOUT_DEFAULT_MS, "这不是数字");
        put(APP_A, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS, "");
        configService.invalidate(APP_A);

        assertThat(configService.getInt(APP_A, TenantConfigKey.TIMEOUT_DEFAULT_MS))
                .as("解析失败回落默认值，而不是把异常抛给调用方")
                .isEqualTo(3000);
        assertThat(configService.getInt(APP_A, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS))
                .isEqualTo(30);
    }

    @Test
    @DisplayName("按租户隔离：甲的配置不影响乙")
    void configsAreIsolatedBetweenTenants() {
        put(APP_A, TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS, "60");
        configService.invalidate(APP_A);

        assertThat(configService.getInt(APP_A, TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS)).isEqualTo(60);
        assertThat(configService.getInt(APP_B, TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS))
                .as("乙没配过，应当拿到默认值 0")
                .isZero();
    }

    @Test
    @DisplayName("非布尔值上的 getBool：读不出来就回落 false，不抛异常")
    void booleanConfig() {
        // 词表里目前没有 BOOL 类型的键（开关那些走的是另一套：SwitchKey）。
        // 这条用例验的是 getBool 的**通用行为**——遇到非布尔值时安静回落，而不是把请求打挂。
        // put 用直接写库（绕过管理面校验），模拟"库里本来就有坏值"的历史数据。
        put(APP_A, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS, "abc");
        configService.invalidate(APP_A);
        assertThat(configService.get(APP_A, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS))
                .as("字符串读取不做校验，原样返回").isEqualTo("abc");
        assertThat(configService.getBool(APP_A, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS))
                .as("'abc' 不是布尔值 → 安静回落 false，不抛异常").isFalse();
        assertThat(configService.getInt(APP_A, TenantConfigKey.CIRCUIT_BREAKER_OPEN_SECONDS))
                .as("'abc' 不是整数 → 回落该键默认值 30").isEqualTo(30);
    }

    // ------------------------------------------------------------------
    // 管理面 HTTP 接口（RQ-1 的写入侧）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("管理面写入 → 立刻生效（不等 30 秒刷新）；删除 → 回落默认值")
    void adminApi_writeThenDelete() throws Exception {
        String url = "/api/apps/" + APP_A + "/config";

        var put = put(url, "{\"rate_limit.warmup_seconds\":\"45\"}", "test-admin-token");
        assertThat(put.getStatusCode().value()).as(String.valueOf(put.getBody())).isEqualTo(200);
        assertThat(configService.getInt(APP_A, TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS))
                .as("写完立刻生效——管理面改动主动失效了缓存").isEqualTo(45);

        var del = rest.exchange(url + "/rate_limit.warmup_seconds",
                org.springframework.http.HttpMethod.DELETE,
                new org.springframework.http.HttpEntity<>(adminHeaders("test-admin-token")), String.class);
        assertThat(del.getStatusCode().value()).isEqualTo(200);
        assertThat(configService.getInt(APP_A, TenantConfigKey.RATE_LIMIT_WARMUP_SECONDS))
                .as("删掉后回落默认值").isZero();
    }

    @Test
    @DisplayName("写入非法值 → 被拒，且**旧值不变**（批量要么全成、要么全不成）")
    void adminApi_rejectsInvalidValue() throws Exception {
        String url = "/api/apps/" + APP_A + "/config";
        put(url, "{\"timeout.default_ms\":\"1200\"}", "test-admin-token");
        assertThat(configService.getInt(APP_A, TenantConfigKey.TIMEOUT_DEFAULT_MS)).isEqualTo(1200);

        var bad = put(url, "{\"timeout.default_ms\":\"一千二\"}", "test-admin-token");
        assertThat(bad.getStatusCode().value()).as("参数错误走业务码 1000").isEqualTo(200);
        assertThat(bad.getBody()).contains("1000").contains("需要整数");
        assertThat(configService.getInt(APP_A, TenantConfigKey.TIMEOUT_DEFAULT_MS))
                .as("旧值保持不变").isEqualTo(1200);
    }

    @Test
    @DisplayName("写入未登记的配置键 → 被拒（词表之外不给写，避免垃圾键堆积）")
    void adminApi_rejectsUnknownKey() throws Exception {
        var bad = put("/api/apps/" + APP_A + "/config", "{\"whatever.key\":\"1\"}", "test-admin-token");

        assertThat(bad.getBody()).contains("未登记的配置键");
    }

    @Test
    @DisplayName("配置变更留审计：谁把哪一项从什么改成了什么")
    void adminApi_changeIsAudited() throws Exception {
        put("/api/apps/" + APP_A + "/config", "{\"circuit_breaker.open_seconds\":\"60\"}", "test-admin-token");

        var logs = logMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                        com.newcoupon.infrastructure.persistence.po.OpenAppConfigLogPO>()
                        .eq("app_id", APP_A)
                        .eq("config_key", "circuit_breaker.open_seconds")
                        .orderByDesc("id"));
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).getNewValue()).isEqualTo("60");
        assertThat(logs.get(0).getOperator()).as("记的是来源 IP").contains("admin@");
    }

    @Test
    @DisplayName("无令牌写配置 → 401（S2 顺带修的缺口：令牌过滤器原先只拦 POST，会漏掉 PUT）")
    void adminApi_withoutToken_rejected() {
        var r = put("/api/apps/" + APP_A + "/config", "{\"timeout.default_ms\":\"999\"}", null);

        assertThat(r.getStatusCode().value())
                .as("PUT 也会改数据，必须和 POST 一样受令牌保护").isEqualTo(401);
        assertThat(r.getBody()).contains("7002");
    }

    // ------------------------------------------------------------------
    // S2 第 10 步·词表对齐：开关项要能按租户配置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("第10步·开关项能按租户配置：PUT switch.<key> 不被词表拒绝")
    void adminApi_switchKeyIsWritable() {
        String url = "/api/apps/" + APP_A + "/config";

        var r = put(url, "{\"switch.receive.enabled\":\"false\"}", "test-admin-token");

        assertThat(r.getBody())
                .as("开关与其它配置项共用同一套写入校验——否则「一键套用预案」第一次调用就整体失败，"
                        + "而且是在出事的时候才发现")
                .contains("\"code\":0");

        // 读回确认真的落库（不是"接口返回成功但什么都没写"）
        var g = rest.exchange(url, org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(adminHeaders("test-admin-token")), String.class);
        assertThat(g.getBody()).contains("switch.receive.enabled").contains("false");
    }

    @Test
    @DisplayName("第10步·开关项只认 true/false：写错被拒（不能把 flase 当成「配过了」）")
    void adminApi_switchKeyRejectsNonBoolean() {
        var bad = put("/api/apps/" + APP_A + "/config",
                "{\"switch.receive.enabled\":\"flase\"}", "test-admin-token");

        assertThat(bad.getBody())
                .as("拼错的布尔值必须被写入校验挡住——写宽容会让「以为关了其实没关」成为常态")
                .contains("true/false");
    }

    @Test
    @DisplayName("第10步·判据不能放宽：长得像开关但没登记的键仍被拒（防「前缀匹配」式实现）")
    void adminApi_unregisteredSwitchLikeKeyStillRejected() {
        // 这条是**防回归**而不是红灯：它今天就是绿的，钉的是"别为了支持开关而把门拆了"——
        // 一旦 fromConfigKey 写成"只要以 switch. 开头就放行"，任意垃圾键都能进配置表
        var bad = put("/api/apps/" + APP_A + "/config",
                "{\"switch.not_a_real_key.enabled\":\"false\"}", "test-admin-token");

        assertThat(bad.getBody()).contains("未登记的配置键");
    }

    // ------------------------------------------------------------------

    private org.springframework.http.ResponseEntity<String> put(String url, String body, String token) {
        return rest.exchange(url, org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(body, adminHeaders(token)), String.class);
    }

    private static org.springframework.http.HttpHeaders adminHeaders(String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("X-Admin-Token", token);
        }
        return headers;
    }

    /**
     * 写一条配置。**先删后插**而不是直接 insert：用例之间共享同一个租户，
     * 直接 insert 会在第二次写同一个键时撞唯一索引——**测试的数据准备也必须是幂等的**
     * （这条同样适用于第 2 步的管理面写入实现：它应当是 upsert 语义）。
     */
    private void put(String appId, TenantConfigKey key, String value) {
        configMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpenAppConfigPO>()
                .eq("app_id", appId).eq("config_key", key.key()));
        OpenAppConfigPO po = new OpenAppConfigPO();
        po.setAppId(appId);
        po.setConfigKey(key.key());
        po.setConfigValue(value);
        configMapper.insert(po);
    }
}
