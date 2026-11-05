package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * V6 4A · **开关关=零变化**的回归承诺钉子（默认上下文：coupon.switch.risk.decision.enabled
 * 未配置任何 true——全站层 yml 显式 false）。
 *
 * <p>承诺的两半（docs/runbook/风控最小版.md §开关）：
 * <ol>
 *   <li>关时**领券全流程既有测试原样绿**——那一半由既有 800+ 例全量基线承担
 *       （它们全部运行在开关关的默认配置下）；</li>
 *   <li>关时**风控路径零副作用**——本类钉：黑名单照建（管理面能力与决策开关无关）、
 *       decide 直接 PASS、risk_decision_log 一行不写（热路径零额外 IO）、
 *       端到端领券成功不返回 7003。</li>
 * </ol>
 *
 * <p>对照类：{@code RiskDecisionIntegrationTest}（开关开，同一批行为的"开"侧）。
 */
@Timeout(120)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskSwitchOffIntegrationTest extends ShardedIntegrationTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_TOKEN = "dev-admin-token";

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate rest;
    @Autowired private com.newcoupon.application.service.RiskDecisionService riskDecisionService;
    @Autowired private com.newcoupon.application.service.RiskListService riskListService;
    @Autowired private DataSource dataSource;

    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();
    private static volatile long templateId;

    @BeforeEach
    void provisionOnce() throws Exception {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        String body = "{\"name\":\"风控4A关侧验证券-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"activityId\":1001,\"couponType\":1,\"faceValue\":100,"
                + "\"threshold\":1000,\"totalCount\":50,\"perUserLimit\":5,"
                + "\"validType\":2,\"validDays\":7}";
        ResponseEntity<String> created = rest.exchange("/api/templates", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode().value()).as("建模板失败: " + created.getBody()).isEqualTo(200);
        templateId = JSON.readTree(created.getBody()).path("data").path("id").asLong();
        ResponseEntity<String> published = rest.exchange("/api/templates/" + templateId + "/publish",
                HttpMethod.POST, new HttpEntity<>("", headers), String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("S1: 黑名单在场+开关关 → decide 直接 PASS 且一行决策日志都不写")
    void switchOffDecideIsNoOp() {
        long userId = 96_500_000L + (System.nanoTime() % 1_000_000L);
        // 管理面能力与决策开关无关：名单照建（运营可以先备名单再开闸灰度）
        riskListService.add(new com.newcoupon.application.dto.RiskDtos.ListAddRequest(
                "BLACK", "USER_ID", String.valueOf(userId), null, "S1 关侧-名单在场", null));
        Integer before = jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log", Integer.class);

        var decision = riskDecisionService.decide(new com.newcoupon.application.service.RiskContext(
                com.newcoupon.domain.risk.RiskScene.RECEIVE, "1", userId,
                "DEV-S1-" + UUID.randomUUID(), "127.0.0.1", "rk-s1-" + UUID.randomUUID()));

        assertThat(decision.action()).as("关=直接 PASS（不评估规则）").isEqualTo(com.newcoupon.domain.risk.RiskAction.PASS);
        assertThat(decision.matchedRules()).isEmpty();
        assertThat(decision.degraded()).isFalse();
        Integer after = jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log", Integer.class);
        assertThat(after).as("关=不写决策日志（热路径零额外 IO 的可观测形态）").isEqualTo(before);
    }

    @Test
    @DisplayName("S2: 端到端——黑名单用户+开关关 → 领券成功不返回 7003（回归零变化）")
    void switchOffReceiveUnaffected() throws Exception {
        long userId = 96_600_000L + (System.nanoTime() % 1_000_000L);
        riskListService.add(new com.newcoupon.application.dto.RiskDtos.ListAddRequest(
                "BLACK", "USER_ID", String.valueOf(userId), null, "S2 关侧-端到端", null));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-Token", ADMIN_TOKEN);
        headers.set("X-Device-Id", "DEV-S2-" + UUID.randomUUID());
        ResponseEntity<String> resp = rest.exchange("/api/coupons/receive", HttpMethod.POST,
                new HttpEntity<>("{\"requestId\":\"rk-s2-" + UUID.randomUUID()
                        + "\",\"userId\":" + userId + ",\"templateId\":" + templateId + "}", headers),
                String.class);
        assertThat(resp.getStatusCode().value()).as("body=" + resp.getBody()).isEqualTo(200);
        assertThat(JSON.readTree(resp.getBody()).path("code").asInt())
                .as("开关关时黑名单用户照常领券——7003 只属于开闸的世界").isZero();
        Integer rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log WHERE user_id = ?", Integer.class, userId);
        assertThat(rows).as("端到端也不留决策日志").isZero();
    }
}
