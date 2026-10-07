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

/**
 * **第 0 步：可行性验证**（2026-10-08，Sentinel 全面迁移的动手前检查）。
 *
 * <p><b>为什么先做这个</b>：迁移方案有三处假设，任何一处不成立都要回头改设计。
 * 与其写完几千行再发现走不通，不如先用最小代价把假设验掉：
 *
 * <ol>
 *   <li><b>Boot 3 适配器能装上吗</b>——官方没有 starter、没有自动配置
 *       （Central 上那个 sentinel-spring-boot-starter 是 2018 年的 0.1.0），
 *       必须手动 {@code addInterceptor}。</li>
 *   <li><b>规则灌进去能真的拦住请求吗</b>——这是整条链路（拦截器 → SlotChain → 规则）
 *       是否接通的最小证明。</li>
 *   <li><b>自建数据源能替代"改造控制台"吗</b>——官方文档说集群流控"必须对接动态规则源、
 *       否则无法正常使用"，还要求改造控制台。我们计划用自己的 MySQL 当规则源；
 *       本类先用 {@code FlowRuleManager.loadRules} 验证"规则确实能被程序化灌入并生效"，
 *       数据源封装（{@code register2Property}）在下一步验证。</li>
 * </ol>
 *
 * <p><b>红灯</b>：本类先于 Sentinel 装配写就——此时规则灌进去也没人看，
 * 两次请求都会 200（而不是第二次 429）。
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                // 关掉 IP 频控：本类要单独验证 Sentinel 的拦截，别被我们自己的风控抢先拒掉
                "coupon.risk.ip-rate.enabled=false"
        })
class SentinelFeasibilityTest extends ShardedIntegrationTestSupport {

    /**
     * 探针资源名 = **匹配到的路径模式**。
     *
     * <p>实测：适配器默认用 {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE} 当资源名，
     * 也就是 Controller 上声明的路径（不含 HTTP 方法前缀，除非开启 {@code setHttpMethodSpecify}）。
     * 第一版我按直觉写成 {@code "sentinel-probe"}，规则灌进去纹丝不动——
     * 因为**没有任何请求的资源名会等于它**，而 Sentinel 对"配了但不匹配任何请求的规则"
     * 是完全静默的（不报错、不告警）。
     */
    private static final String PROBE_RESOURCE = "/api/coupons/receive";

    @Autowired
    private TestRestTemplate rest;

    @AfterEach
    void clearRules() {
        // 规则是**全局静态**状态（FlowRuleManager 是 JVM 级单例），不清会污染别的用例
        FlowRuleManager.loadRules(List.of());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("可行性①：规则灌进去后真的能拦住请求（用 count=0，不依赖请求快慢）")
    void flowRuleActuallyBlocks() {
        // **用 `count=0` 而不是 `count=1`**：Sentinel 里 0 表示"全部拒绝"，一发就拦，
        // 与请求快慢、统计窗口、秒边界都无关。
        //
        // 这里踩过一个大坑，值得记：我最初写的是 `count=1` + "连打 20 次至少拦一次"，
        // 结果 20 次**全都放行**。加了耗时测量才看清楚——**每次请求要 1.3 秒，20 次跨了 26 秒**，
        // 而 1 QPS 正好是"每秒放一个"，于是**永远不超限**。
        // 集成测试的请求很慢（要走完整个 Spring + 数据库链路），
        // 所以"连打 N 次"这类判据在集成测试里几乎总是错的——这与我在限流用例里
        // 反复踩的"用不可控的量卡边界"是同一个坑，只是这次的量是**请求耗时**。
        FlowRule rule = new FlowRule();
        rule.setResource(PROBE_RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);              // 0 = 全部拒绝
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));

        var blocked = callProbe();
        assertThat(blocked.getStatusCode().value())
                .as("count=0 的规则下，第一发就该被拦。若仍是 200，说明拦截器没装上或规则没生效——"
                        + "整条链路没接通，迁移方案不成立。实际响应：" + blocked.getBody())
                .isEqualTo(429);
        assertThat(blocked.getBody())
                .as("拦下时应当是 Sentinel 的默认处理器（这一步先确认是它拦的，"
                        + "第 4 步才会换成我们自己的 429 契约）")
                .contains("Blocked by Sentinel");
    }

    @Test
    @DisplayName("可行性②：没有规则时不拦（证明上一条的 429 来自规则，不是别的东西）")
    void withoutRuleNothingBlocks() {
        FlowRuleManager.loadRules(List.of());

        assertThat(callProbe().getStatusCode().value()).isEqualTo(200);
        assertThat(callProbe().getStatusCode().value())
                .as("没有规则就该一路放行——否则上一条的 429 无法归因到规则上")
                .isEqualTo(200);
    }

    /**
     * 打一发会经过探针资源的请求。
     *
     * <p>用内部面 {@code /api/coupons/receive}：它同时挂了 {@code /open} 前缀，
     * 路径稳定、无签名要求，适合做"这条链路通不通"的探针。
     * （模板 id 故意用一个不存在的值——业务会报错，但那不影响我们看 HTTP 状态码。）
     */
    private org.springframework.http.ResponseEntity<String> callProbe() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/coupons/receive",
                new HttpEntity<>("{\"requestId\":\"feasibility-" + System.nanoTime()
                        + "\",\"userId\":1,\"templateId\":999999}", headers), String.class);
    }
}
