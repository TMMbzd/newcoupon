package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * S1 第 9 步：开关中心（RQ-10）——<b>红绿灯</b>。
 *
 * <p>红：本类先于 {@code SwitchGuardInterceptor} 写就——此时把开关配成 false 也没人看，
 * 领券照样返回业务错误（而不是 5001）。
 *
 * <p><b>关掉接收与锁券两个开关</b>（用 {@code @SpringBootTest} 的 properties），
 * 一次跑完覆盖四件事：
 * <ol>
 *   <li>关闭的接口 → 5001（可重试语义，不是"不可用"）；</li>
 *   <li>没关的接口照常工作（对照——证明不是"整个系统被关死了"）；</li>
 *   <li>读取接口能列出**全部**开关（含未配置的）与人话描述；</li>
 *   <li>{@code configured=false} 表示"正在用默认值"——排障时的关键信息。</li>
 * </ol>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.sharding.bucket-size=10000",
                "coupon.switch.receive.enabled=false",       // 关掉领券
                "coupon.switch.trade.lock.enabled=false"     // 关掉锁券
        })
class SwitchCenterIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("关掉的接口 → 5001 可重试（不是 404、不是 500，也不是业务错误）")
    void receiveDisabled_returnsDegraded() {
        ResponseEntity<String> r = post("/api/coupons/receive", """
                {"requestId":"sw-1","userId":1,"templateId":1}""");

        assertThat(r.getStatusCode().value()).as("5xxx 走既有的 HTTP 200 + 业务码约定").isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":5001");
        assertThat(r.getBody()).as("降级信息要说清是哪个功能被关了").contains("领券入口总开关");
    }

    @Test
    @DisplayName("另一个关掉的接口 → 5001（开关是按接口粒度生效的）")
    void lockDisabled_returnsDegraded() {
        ResponseEntity<String> r = post("/api/coupons/lock", """
                {"orderNo":"sw-order-1","userId":1,"couponId":1}""");

        assertThat(r.getBody()).contains("\"code\":5001");
        assertThat(r.getBody()).contains("锁券入口开关");
    }

    @Test
    @DisplayName("没被关的功能照常工作——开关不是把系统焊死")
    void otherEndpointsUnaffected() {
        // 查券包没挂开关，照常走业务（用户不存在 → 空列表，code=0）
        ResponseEntity<String> r = rest.getForEntity("/api/coupons?userId=123456", String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("\"code\":0");
    }

    @Test
    @DisplayName("读取接口：列出全部开关（含未配置的），并标出'是否被显式配置过'")
    void switchList_showsAllWithState() throws Exception {
        ResponseEntity<String> r = rest.getForEntity("/api/switches", String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        JsonNode switches = objectMapper.readTree(r.getBody()).path("data");
        assertThat(switches.size()).as("清单是显式登记的枚举——没有它就没人知道能关什么")
                .isGreaterThanOrEqualTo(6);

        JsonNode receive = find(switches, "receive.enabled");
        assertThat(receive.path("on").asBoolean()).isFalse();
        assertThat(receive.path("configured").asBoolean()).as("显式配成了 false").isTrue();
        assertThat(receive.path("description").asText()).contains("领券入口");

        JsonNode outbox = find(switches, "outbox.dispatch.enabled");
        assertThat(outbox.path("on").asBoolean()).as("未配置 = 默认开启").isTrue();
        assertThat(outbox.path("configured").asBoolean())
                .as("configured=false 是排障的关键信息：'我明明配了怎么没生效' → 一看就知道配错了地方")
                .isFalse();
    }

    // ------------------------------------------------------------------

    private JsonNode find(JsonNode array, String key) {
        for (JsonNode node : array) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("开关清单里没有 " + key + "：" + array);
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
