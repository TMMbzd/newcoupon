package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    /** 本用例开始前的系统规则（**用例里取**：类加载时 Spring 上下文还没起来，那时还是空的） */
    private static List<SystemRule> rulesBeforeTest;

    @Autowired
    private TestRestTemplate rest;

    /**
     * 用完**恢复启动时的规则**，而不是清空。
     *
     * <p>第 8 步修：原来这里是 {@code loadRules(List.of())}——那会把启动时加载的
     * **生产默认系统规则**一起清掉，而且因为是 JVM 级静态状态，还会影响同一 JVM 里
     * 后续的用例（"顺序相关"的那类问题，第 6 步刚踩过同款）。
     */
    @BeforeEach
    void snapshotRules() {
        rulesBeforeTest = SystemRuleManager.getRules();
    }

    @AfterEach
    void restoreRules() {
        SystemRuleManager.loadRules(rulesBeforeTest);
    }

    private ResponseEntity<String> call() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(RESOURCE,
                new HttpEntity<>("{\"requestId\":\"block-" + System.nanoTime()
                        + "\",\"userId\":1,\"templateId\":999999}", h), String.class);
    }

    /**
     * 拦下**所有**请求：用系统规则（"系统总 QPS 不能超过 0"），而不是 FlowRule。
     *
     * <p><b>为什么从 FlowRule 换成 SystemRule（第 6 步改）</b>：{@code FlowRuleManager} 的规则位
     * **已经被自建的规则源接管了**（{@code SentinelRuleConfig} → 那个数据源每 10 秒把
     * 全量租户规则推一次）。手工 {@code loadRules} 灌的规则会被下一次刷新**整个冲掉**——
     * 实测症状就是这条用例在全量里红、单跑绿（"预期 429，实际 200"）。
     *
     * <p>这不是新问题：第 3 步就在另一个用例里踩过同一件事（"数据源与 loadRules 互相覆盖"），
     * 当时的结论是"测试要走生产同一条路径"。本类保留的价值是**拒绝响应的形状**——
     * 而"形状"与"是哪条规则拦的"无关，所以改用 {@code SystemRuleManager}
     * （规则源不碰它，因此确定、不受刷新影响）。
     *
     * <p>顺带覆盖到一处本来没人验的映射：{@code SystemBlockException} → 5000（系统繁忙）
     * 而不是 6006（请求超限）。6006/6007 这两条映射由
     * {@code OpenApiQuotaIntegrationTest} 与 {@code SentinelIpDimensionTest} 覆盖。
     */
    private void blockAllBySystemRule() {
        SystemRule rule = new SystemRule();
        // 0 = "系统总 QPS 超过 0 就拒"。源码核实：SystemRuleManager 的判据是
        // `getQps() >= 0`（所以 0 是**有效配置**，不是"没配"），而比较是
        // `当前QPS + 本次count > 阈值` → 1 > 0 恒真，一发就拦，与请求快慢无关。
        rule.setQps(0);
        SystemRuleManager.loadRules(List.of(rule));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("被限时：HTTP 429 + **JSON 业务码** + Retry-After + 说明维度（四要素齐全）")
    void blockResponseKeepsOurContract() {
        blockAllBySystemRule();

        var resp = call();

        assertThat(resp.getStatusCode().value())
                .as("**系统过载用 503，不是 429**（第 8 步改）：429 的语义是「你打太快了，慢点再来」，"
                        + "503 才是「这台机器现在扛不住」。接入方对两者的处置完全不同——"
                        + "前者该退避重试同一个实例，后者该认为整个服务不可用。业务码里的 5000（系统繁忙）"
                        + "本来就是按这个语义定的，状态码一直没跟上。实际=" + resp.getStatusCode().value())
                .isEqualTo(503);
        assertThat(resp.getBody())
                .as("必须是本项目的 JSON 格式，而不是 Sentinel 默认的纯文本。实际=" + resp.getBody())
                .startsWith("{");
        assertThat(resp.getBody())
                .as("业务码要与迁移前一致（5000=系统繁忙；系统自适应保护不是「谁打太快」，"
                        + "所以它不该用 6006）").contains("5000");
        assertThat(resp.getHeaders().getFirst("Retry-After"))
                .as("429 必须告诉对方多久后重试——否则接入方只能瞎猜或死循环重试").isNotNull();
        assertThat(resp.getBody())
                .as("要说清是哪个维度被限 + 是哪个资源（接入方据此区分「我打太快」"
                        + "与「接口被平台限了」，也便于排障定位）。实际=" + resp.getBody())
                .contains("自适应")
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
