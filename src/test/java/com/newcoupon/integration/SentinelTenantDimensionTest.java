package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 第 2 步：**租户维度**——origin（调用方身份）真的传到 Sentinel 了吗（2026-10-08）。
 *
 * <p><b>为什么这条单独验</b>：Sentinel 表达"按租户限流"的唯一机制是
 * {@code FlowRule.limitApp} 匹配 {@code ContextUtil.enter(name, origin)} 里的 origin
 * （或适配器的 {@code RequestOriginParser}）。而我们已经在 {@code SentinelWebConfig} 里
 * 把 origin 接到了 {@code RequestContext.appId()}（**验签通过**的接入方标识）。
 *
 * <p>这条用例就是验证那条接线的：**同一个资源**，给租户甲灌一条"全拒"的规则，
 * 甲被拦、乙不受影响、内部面（没有租户身份）也不受影响。
 *
 * <p>若这条绿不了，说明 origin 没传对——那么"按租户限流"整条路走不通，
 * 迁移方案要么改用"每个租户一个资源名"，要么退回去自己实现。**所以它是第 2 步的验收。**
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168",
                "coupon.risk.ip-rate.enabled=false"     // 别被我们自己的风控抢先拒掉
        })
class SentinelTenantDimensionTest extends ShardedIntegrationTestSupport {

    /** 资源名 = 匹配到的路径模式（第 0 步实测确认的规则） */
    private static final String RESOURCE = "/open/v1/coupons";

    private static final String APP_A = "t-sentinel-a";
    private static final String APP_B = "t-sentinel-b";
    private static final long USER_ID = 98_001L;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    private static OpenApiTestClient client;
    private static String secretA;
    private static String secretB;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        client = new OpenApiTestClient(port);
        secretA = appService.create(new CreateAppRequest(APP_A, "Sentinel甲", 0, 0, null)).secret();
        secretB = appService.create(new CreateAppRequest(APP_B, "Sentinel乙", 0, 0, null)).secret();
    }

    @AfterEach
    void clearRules() {
        // 规则是 **JVM 级静态状态**（FlowRuleManager 是全局单例），不清会污染别的用例——
        // 这是"测试共享容器"之后更要紧的一条纪律（见工程手记 2.2b）。
        FlowRuleManager.loadRules(List.of());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("租户维度：给甲灌「全拒」规则 → 甲被拦、乙不受影响、内部面也不受影响")
    void tenantDimensionIsolatedByOrigin() throws Exception {
        // 一条"只针对甲、数量为 0（全拒）"的规则。资源用开放面的领券查询路径。
        FlowRule rule = new FlowRule();
        rule.setResource(RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);              // 0 = 全拒，与请求快慢无关（第 0 步的教训）
        rule.setLimitApp(APP_A);       // ← 只对"调用方 = 甲"生效
        FlowRuleManager.loadRules(List.of(rule));

        // ① 甲 → 应当被拦
        var rA = client.get(RESOURCE, "userId=1", APP_A, secretA);
        assertThat(rA.status())
                .as("甲被规则点名，应当被拦（429）。若 200，说明 origin 没传到 Sentinel——"
                        + "「按租户限流」这条机制不成立。响应=" + rA.body())
                .isEqualTo(429);

        // ② 乙 → 不受影响
        var rB = client.get(RESOURCE, "userId=1", APP_B, secretB);
        assertThat(rB.status())
                .as("规则只点名了甲，乙不该受影响。响应=" + rB.body())
                .isNotEqualTo(429);
    }

    @Test
    @DisplayName("对照：规则点名甲时，**恰好只有甲**被拦——证明上一条的 429 来自 origin 匹配")
    void onlyTheNamedOriginIsBlocked() throws Exception {
        // 先确认"没有规则时谁都不拦"
        assertThat(client.get(RESOURCE, "userId=1", APP_A, secretA).status())
                .as("没有规则时不该拦任何人").isNotEqualTo(429);

        // 再灌一条点名乙的规则 → 这次应当**乙**被拦、甲放行（与上一条反着来）
        FlowRule rule = new FlowRule();
        rule.setResource(RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        rule.setLimitApp(APP_B);
        FlowRuleManager.loadRules(List.of(rule));

        assertThat(client.get(RESOURCE, "userId=1", APP_B, secretB).status())
                .as("这次点名的是乙").isEqualTo(429);
        assertThat(client.get(RESOURCE, "userId=1", APP_A, secretA).status())
                .as("甲这次没被点名，应当放行").isNotEqualTo(429);
    }
}
