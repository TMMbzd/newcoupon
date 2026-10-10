package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import java.util.concurrent.TimeUnit;
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

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // 2026-10-10 环0 补写：本类此前漏写注入块，一直在悄悄连 dev 库（dev 容器停了才暴露）
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }


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

    // **@AfterEach 清理已删（第 6 步）**：本类原来在用例后清 FlowRuleManager，
    // 因为那时规则是它自己灌的。现在规则由自建规则源接管、本类也不再灌规则，
    // 而"清空全局规则"反而会误伤别的用例。本类留下的只有 Sentinel 的统计计数
    // （ClusterNode），它不参与任何断言之外的行为。

    // ------------------------------------------------------------------

    @Test
    @DisplayName("可行性①：适配器真的装上了——请求走完之后，这个资源在 Sentinel 里有统计")
    void adapterRecordsTheResource() {
        // **为什么这条断言改成看统计，而不是"灌规则 → 看 429"（第 6 步改）**：
        // `FlowRuleManager` 的规则位已经被自建的规则源接管（每 10 秒全量推一次），
        // 手工 `loadRules` 灌的规则会被下一次刷新冲掉——那样这条用例就变成"单跑绿、全量红"。
        // 而本类当初要回答的问题其实是**前半个**：拦截器装上了吗？资源名对吗？
        // （第 0 步的坑：第一版按直觉把资源名写成 "sentinel-probe"，规则灌进去纹丝不动，
        //   因为没有任何请求的资源名会等于它。）
        // 这两件事用**统计**来验更直接，而且与"规则归谁管"无关。
        //
        // "规则能拦住请求"这件事仍然被测——走的是生产同一条路径（配置 → 数据源 → 规则），
        // 见 SentinelTenantDimensionTest 与 SentinelIpDimensionTest。
        ClusterNode before = ClusterBuilderSlot.getClusterNode(PROBE_RESOURCE, EntryType.IN);
        long beforeCount = before == null ? 0 : before.totalRequest();

        var resp = callProbe();
        assertThat(resp.getStatusCode().value())
                .as("探针接口本身要通（业务上是「模板不存在」，不是被拦）。实际响应：" + resp.getBody())
                .isEqualTo(200);

        ClusterNode node = ClusterBuilderSlot.getClusterNode(PROBE_RESOURCE, EntryType.IN);
        assertThat(node)
                .as("资源名对不上就永远不会有节点——这正是第 0 步踩过的那个坑。"
                        + "所以这条断言验的是「拦截器装上了」+「资源名就是匹配到的路径模式」")
                .isNotNull();
        assertThat(node.totalRequest())
                .as("这一发请求必须被 Sentinel 记到账上（**与请求前比**：ClusterNode 是 JVM 级的，"
                        + "别的上下文可能早就打过同一个资源，所以不能直接断言大于 0）")
                .isGreaterThan(beforeCount);
    }

    @Test
    @DisplayName("可行性②（对照）：探针接口在没有规则时是通的")
    void withoutRuleNothingBlocks() {
        assertThat(callProbe().getStatusCode().value())
                .as("没有规则就该一路放行——否则上一条里「资源被 Sentinel 看见」这件事"
                        + "就无法与「被别的规则拦下」区分开")
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
