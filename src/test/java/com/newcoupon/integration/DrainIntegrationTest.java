package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * drain 摘流量端点验收（V6 5B 生产化模拟刀 A1：{@code POST /internal/admin/drain} 先摘流量再 SIGTERM）。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li><b>认证归属</b>：/internal/admin/** 与 /api 管理面同一判据（旧令牌或会话）——
 *       摘流量是"把实例请出集群"的能力，裸奔等于任何人都能把整个集群逐台摘光。</li>
 *   <li><b>摘流量的实现方式</b>：drain 后 /actuator/health 变 503/DOWN（LB 探测口径），
 *       其余一切照旧——不关端口、不断连接。</li>
 *   <li><b>业务不受影响</b>：drain 后业务请求（领券）照常 200/code=0。这是设计不是漏洞：
 *       drain 只对"要不要给这台实例**新**流量"表态，已进来的和在途的请求由 graceful
 *       shutdown 收尾；单实例直连场景（没有 LB）业务口继续可用。</li>
 *   <li><b>undrain 兜底</b>：摘错了不用重启实例，health 立即回 UP。</li>
 * </ol>
 *
 * <p>状态纪律（共享库基座）：drain 是 JVM 级全局标志，本类用例结束后必须 undrain 复位，
 * 否则泄漏给共享同一上下文缓存的其他用例（它们的 /actuator/health 会莫名 503）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DrainIntegrationTest extends ShardedIntegrationTestSupport {

    /** dev profile 的便利令牌（application-dev.yml）——与 P2cAdminAuthTest 同源 */
    private static final String ADMIN_TOKEN = "dev-admin-token";

    private static final JsonMapper JSON = new JsonMapper();

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    @AfterEach
    void ensureUndrained() {
        // 兜底复位：断言中途失败也不能把 drained 状态泄漏给共享上下文的后续用例
        postWithToken("/internal/admin/undrain", "");
    }

    // ------------------------------------------------------------------
    // ① 认证：/internal/admin/** 归管理面
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未认证 drain/undrain → 401（7002），与 /api 管理面同一拒绝形状")
    void drainWithoutToken_isRejected() {
        for (String path : new String[]{"/internal/admin/drain", "/internal/admin/undrain"}) {
            ResponseEntity<String> r = rest.postForEntity(path, emptyJsonBody(), String.class);
            assertThat(r.getStatusCode().value()).as(path + " 未认证应 401").isEqualTo(401);
            assertThat(r.getBody()).as(path + " 拒绝体带 7002").contains("7002");
        }

        HttpHeaders wrong = jsonHeaders();
        wrong.set("X-Admin-Token", "wrong-token");
        ResponseEntity<String> wrongResp =
                rest.exchange("/internal/admin/drain", HttpMethod.POST, new HttpEntity<>("", wrong), String.class);
        assertThat(wrongResp.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // ② 摘流量语义：health 503，业务照常，undrain 恢复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("drain → health 503/DOWN 且领券照常 200；undrain → health 回 UP")
    void drainFlipsHealthButNotBusinessThenUndrainRestores() throws Exception {
        // 前置：health 正常（本用例之前没人 drain 过——AfterEach 兜底保证）
        assertThat(healthStatus()).isEqualTo(200);

        long templateId = createAndPublishTemplate();

        // drain：置全局标志，返回体 data=true（当前已摘除）
        ResponseEntity<String> drainResp = postWithToken("/internal/admin/drain", "");
        assertThat(drainResp.getStatusCode().value()).isEqualTo(200);
        assertThat(codeOf(drainResp.getBody())).isZero();
        assertThat(JSON.readTree(drainResp.getBody()).path("data").asBoolean())
                .as("drain 后 data 应为 true（当前处于摘除态）").isTrue();

        // 摘流量 = health 变 503：这是给 LB 看的口径（被动摘除的触发信号）
        assertThat(healthStatus())
                .as("drain 后 /actuator/health 应 503").isEqualTo(503);

        // 业务照常：领券在摘除态下仍然成功——drain 只影响健康检查，不影响业务处理。
        // 这是设计不是漏洞：LB 摘除后不会再有新流量进来；已进来的与在途的请求
        // 继续处理完，等 SIGTERM 后由 graceful shutdown（30s）收尾。
        Long userId = 96_600_000_000L + UUID.randomUUID().getLeastSignificantBits() % 1_000_000L;
        ResponseEntity<String> receive = rest.exchange("/api/coupons/receive", HttpMethod.POST,
                new HttpEntity<>("""
                        {"requestId":"drain-it-%s","userId":%d,"templateId":%d}"""
                        .formatted(UUID.randomUUID(), userId, templateId), jsonHeaders()),
                String.class);
        assertThat(receive.getStatusCode().value())
                .as("drain 后业务口照常服务（HTTP 层）").isEqualTo(200);
        assertThat(codeOf(receive.getBody()))
                .as("drain 后领券业务码应为 0——业务不受摘除影响").isZero();

        // undrain：运维兜底（摘错了不用重启），health 立即回 UP
        ResponseEntity<String> undrainResp = postWithToken("/internal/admin/undrain", "");
        assertThat(undrainResp.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(undrainResp.getBody()).path("data").asBoolean())
                .as("undrain 后 data 应为 false（当前处于服务态）").isFalse();
        assertThat(healthStatus())
                .as("undrain 后 /actuator/health 应回 200").isEqualTo(200);
    }

    @Test
    @DisplayName("undrain 幂等：未 drain 时 undrain 也是 200（data=false），不报错")
    void undrainWithoutDrain_isIdempotent() throws Exception {
        ResponseEntity<String> r = postWithToken("/internal/admin/undrain", "");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(r.getBody()).path("data").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private int healthStatus() {
        return rest.getForEntity("/actuator/health", String.class).getStatusCode().value();
    }

    private ResponseEntity<String> postWithToken(String path, String body) {
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpEntity<String> emptyJsonBody() {
        return new HttpEntity<>("", jsonHeaders());
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private int codeOf(String body) throws Exception {
        return JSON.readTree(body).path("code").asInt(-1);
    }

    /** 建并发布一个可领取的模板（与 P2cAdminAuthTest 的建券形状一致，名字带唯一尾缀） */
    private long createAndPublishTemplate() throws Exception {
        String body = """
                {"name":"DRAIN-IT-%s","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":100,"perUserLimit":1,
                 "validType":2,"validDays":7}""".formatted(UUID.randomUUID());
        ResponseEntity<String> created = postWithToken("/api/templates", body);
        assertThat(created.getStatusCode().value()).as("建模板失败: " + created.getBody()).isEqualTo(200);
        long id = JSON.readTree(created.getBody()).path("data").path("id").asLong();

        ResponseEntity<String> published =
                postWithToken("/api/templates/" + id + "/publish", "");
        assertThat(published.getStatusCode().value()).as("发布失败: " + published.getBody()).isEqualTo(200);
        return id;
    }
}
