package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 第 4 步：**拒绝响应要守住本项目的契约**（2026-10-08）。
 *
 * <p><b>红</b>：Sentinel 的默认处理器只回 {@code 429 + 纯文本 "Blocked by Sentinel (flow limiting)"}——
 * 没有 JSON、没有业务码、没有 Retry-After、也没说是哪一维度被限。
 * 而本项目的契约（S1/S2 一路建立的）是**四要素齐全**：
 *
 * <pre>
 *   HTTP 429 + {"code":6006,...} + Retry-After + 说明是哪个维度
 * </pre>
 *
 * <p>接入方靠这些做重试决策（"多久后重试"）与排障（"是我打太快还是这个接口被限了"），
 * 少了任何一样都是**对外的行为退化**——所以这是迁移必须保住的东西，不是可选的美化。
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.risk.ip-rate.enabled=false"     // 别让风控抢先拒掉，本类只验 Sentinel 的拒绝形态
        })
class SentinelBlockContractTest extends ShardedIntegrationTestSupport {

    /** 用内部面：它没有 origin，所以配 limitApp=default 的规则即可命中，不必造签名请求 */
    private static final String RESOURCE = "/api/coupons/receive";

    @Autowired
    private TestRestTemplate rest;

    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(List.of());   // 规则是 JVM 级静态状态，用完必须清
    }

    private ResponseEntity<String> call() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(RESOURCE,
                new HttpEntity<>("{\"requestId\":\"block-" + System.nanoTime()
                        + "\",\"userId\":1,\"templateId\":999999}", h), String.class);
    }

    /** 灌一条"全拒"的规则：count=0 在 Sentinel 里表示全部拒绝，一发就拦，与请求快慢无关 */
    private void rejectAll() {
        FlowRule rule = new FlowRule();
        rule.setResource(RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("被限时：HTTP 429 + **JSON 业务码** + Retry-After + 说明维度（四要素齐全）")
    void blockResponseKeepsOurContract() {
        rejectAll();

        var resp = call();

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(resp.getBody())
                .as("必须是本项目的 JSON 格式，而不是 Sentinel 默认的纯文本。实际=" + resp.getBody())
                .startsWith("{");
        assertThat(resp.getBody())
                .as("业务码要与迁移前一致（6006=请求速率超限）").contains("6006");
        assertThat(resp.getHeaders().getFirst("Retry-After"))
                .as("429 必须告诉对方多久后重试——否则接入方只能瞎猜或死循环重试").isNotNull();
        assertThat(resp.getBody())
                .as("要说清是哪个维度被限 + 是哪个资源（接入方据此区分「我打太快」"
                        + "与「接口被平台限了」，也便于排障定位）。实际=" + resp.getBody())
                .contains("超限")
                .contains(RESOURCE);
    }

    @Test
    @DisplayName("对照：没有规则时响应照常（证明上一条的 429 来自 Sentinel，不是别的东西）")
    void withoutRuleNormalResponse() {
        var resp = call();

        assertThat(resp.getStatusCode().value())
                .as("没有规则就不该被拦；这里应当是业务错误（模板不存在），而不是 429")
                .isEqualTo(200);
        assertThat(resp.getBody()).contains("2001");
    }
}
