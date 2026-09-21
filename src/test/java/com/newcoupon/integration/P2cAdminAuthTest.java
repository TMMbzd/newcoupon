package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * P2c 第一步：管理端点最小鉴权（L27 清偿，2026-09-21）。
 *
 * <p>背景：三视角审查实锤 publish 端点裸奔——P2b 后它的效力从"重置 Redis 记账"升格为
 * "铸造 DB allocated 配额+开桶"，无鉴权=凭空造额度入口。G 簇裁决最小鉴权提前。
 *
 * <p>测的是 HTTP 层（真正的暴露面）：无令牌→401；错令牌→401；对令牌→放行（业务正常执行）。
 * 服务层集成测不经 HTTP，不受影响。
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.sharding.bucket-size=10000",
                "coupon.sharding.bucket-count=16",
                "coupon.admin.token=test-admin-token"     // 本类显式配死令牌，不依赖 dev 默认
        })
class P2cAdminAuthTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    private static final String CREATE_BODY = """
            {"name":"鉴权验证券","activityId":1001,"couponType":1,"faceValue":1000,
             "threshold":10000,"totalCount":100,"perUserLimit":1,
             "validType":2,"validDays":7}""";

    @Test
    @DisplayName("无令牌建券 → 401（错误码 7002）")
    void create_withoutToken_rejected() {
        ResponseEntity<String> r = postCreate(null);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("7002");
    }

    @Test
    @DisplayName("错令牌 → 401")
    void create_wrongToken_rejected() {
        ResponseEntity<String> r = postCreate("wrong-token");
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("对令牌 → 放行（建券正常执行）")
    void create_correctToken_passes() {
        ResponseEntity<String> r = postCreate("test-admin-token");
        assertThat(r.getStatusCode().value()).as("对令牌应过闸（业务结果 200）: " + r.getBody()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET 查询不设防（只读面公开）")
    void query_notGuarded() {
        ResponseEntity<String> r = rest.getForEntity("/api/templates/999999", String.class);
        assertThat(r.getStatusCode().value()).isNotEqualTo(401);   // 404/业务错误都行——只要不是鉴权拦
    }

    private ResponseEntity<String> postCreate(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("X-Admin-Token", token);
        }
        return rest.exchange("/api/templates", HttpMethod.POST,
                new HttpEntity<>(CREATE_BODY, headers), String.class);
    }
}
