package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.infrastructure.persistence.mapper.OpenApiAuditMapper;
import com.newcoupon.infrastructure.persistence.po.OpenApiAuditPO;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * S1 第 10 步：调用审计（RQ-11）——<b>红绿灯</b>。
 *
 * <p>红：本类先于审计记录器接线写就——此时 {@code open_api_audit} 表一行都不会有。
 *
 * <p><b>三条用例各钉一件事</b>：
 * <ol>
 *   <li>成功的调用留痕（appId / 资源名 / 业务码 / 耗时）；</li>
 *   <li><b>验签失败也要留痕</b>——审计一半的价值在这里：谁在敲门、敲了多少次；</li>
 *   <li>连签名头都没有的请求也要留一行（appId 为空，但 path 与来源 IP 在）。</li>
 * </ol>
 *
 * <p>断言直接查库：审计是**同步落表**的（在过滤器 finally 里），请求返回时那行已经在表里，
 * 不需要轮询等待——这正是 S1 选同步的理由（可信、可测）。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class OpenApiAuditIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final String APP_ID = "t-audit";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    @Autowired
    private OpenApiAuditMapper auditMapper;

    private static OpenApiTestClient client;
    private static String secret;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        client = new OpenApiTestClient(port);
        secret = appService.create(new CreateAppRequest(APP_ID, "审计测试接入方", null, null, null)).secret();
    }

    @Test
    @DisplayName("成功调用：审计一行，含 appId / 资源名 / 业务码 / 耗时 / traceId")
    void successfulCall_isAudited() throws Exception {
        OpenApiTestClient.Response r = client.get("/open/v1/coupons", "userId=1", APP_ID, secret);
        assertThat(r.status()).isEqualTo(200);

        OpenApiAuditPO row = latest();
        assertThat(row.getAppId()).isEqualTo(APP_ID);
        assertThat(row.getVerified()).isEqualTo(1);
        assertThat(row.getResource()).as("资源名来自 @OpenResource 注解").isEqualTo("coupons/query");
        assertThat(row.getCode()).as("业务码：成功 = 0").isZero();
        assertThat(row.getHttpStatus()).isEqualTo(200);
        assertThat(row.getTraceId()).isNotBlank();
        assertThat(row.getCostMs()).isNotNull();
        assertThat(row.getPath()).isEqualTo("/open/v1/coupons");
    }

    @Test
    @DisplayName("验签失败：**也要留痕**——记的是'声明值'，并用 verified 标记它没进门")
    void failedSignature_isAlsoAudited() throws Exception {
        OpenApiTestClient.Response r = client.get("/open/v1/coupons", "userId=1", APP_ID, "wrong-secret");
        assertThat(r.status()).isEqualTo(401);

        OpenApiAuditPO row = latest();
        assertThat(row.getAppId()).as("谁在敲门（声明的 appId）").isEqualTo(APP_ID);
        assertThat(row.getVerified()).as("但明确标记：未通过验签").isZero();
        assertThat(row.getCode()).isEqualTo(6003);
        assertThat(row.getHttpStatus()).isEqualTo(401);
        assertThat(row.getClientIp()).as("来源 IP 也要记——排查刷接口的第一手信息").isNotBlank();
    }

    @Test
    @DisplayName("连签名头都没有：也留一行（appId 为空，但路径与来源在）")
    void missingHeader_isAuditedToo() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/open/v1/coupons?userId=1"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);

        OpenApiAuditPO row = latest();
        assertThat(row.getAppId()).isNull();
        assertThat(row.getVerified()).isZero();
        assertThat(row.getCode()).isEqualTo(6001);
        assertThat(row.getPath()).isEqualTo("/open/v1/coupons");
    }

    /**
     * **多级代理下取哪一跳**（2026-10-08 第 6.5 步）。
     *
     * <p><b>红</b>：改前取的是 {@code X-Forwarded-For} 的**最左**那个值——而最左边恰恰是
     * 请求方自己塞得进去的位置（代理通常是往右边**追加**真实来源）。于是"审计记的来源 IP"
     * 变成了攻击者写的字符串，而按 IP 的限流只要每次换个假地址就失效。
     *
     * <p><b>改后</b>：由容器（Tomcat {@code RemoteIpValve}）判定——它只看"直连对端是否可信代理"，
     * 然后**从右往左跳过可信跳、取第一个不可信的**。所以本用例里攻击者塞的 {@code 1.2.3.4}
     * 会被跳过，取到的是真实来源 {@code 198.51.100.7}。
     *
     * <p>这条断言是这次改动的**唯一回归钉子**：仓库里此前没有任何用例断言过
     * "IP 等于头里的某一个值"（审计用例只断言过非空），所以语义变了也没人会红。
     */
    @Test
    @DisplayName("多级代理：来源 IP 取「从右往左第一个不可信跳」，不是最左那个")
    void clientIpIgnoresSpoofedLeftmostHop() throws Exception {
        // 真实形态：客户端(198.51.100.7) → 我们的 Nginx(127.0.0.1，可信) → 应用。
        // 攻击者在最左边塞了一个假地址——这正是"取首跳"会被骗的地方。
        OpenApiTestClient.Response r = client.get("/open/v1/coupons", "userId=1", APP_ID, secret,
                "X-Forwarded-For", "1.2.3.4, 198.51.100.7");
        assertThat(r.status()).as("这一发本身要通（审计留痕与状态码无关）").isEqualTo(200);

        OpenApiAuditPO row = latest();
        assertThat(row.getClientIp())
                .as("取最左 = 攻击者想写什么就写什么：限流键与审计来源全被污染。"
                        + "取最右的不可信跳才是客户端地址。实际记的是=" + row.getClientIp())
                .isEqualTo("198.51.100.7");
    }

    // ------------------------------------------------------------------

    private OpenApiAuditPO latest() {
        List<OpenApiAuditPO> rows = auditMapper.selectList(
                new QueryWrapper<OpenApiAuditPO>().orderByDesc("id").last("LIMIT 1"));
        assertThat(rows).as("审计表里应当有记录").isNotEmpty();
        return rows.get(0);
    }
}
