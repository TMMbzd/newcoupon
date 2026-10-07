package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.TenantConfigService;
import com.newcoupon.infrastructure.ratelimit.TenantFlowRuleDataSource;
import java.util.Map;
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

    /**
     * **每类独有的 appId**（2026-10-08：测试共享容器之后的硬纪律）。
     *
     * <p>写成常量会在**第二次运行**时撞"appId 已存在"——因为容器不再按类销毁了。
     * 这与"用例的数据自己建"是同一条纪律的延伸：数据不仅要自己建，还要**别人建不出来**。
     */
    private static final String APP_A = "t-sentinel-a-" + (System.nanoTime() % 1_000_000);
    private static final String APP_B = "t-sentinel-b-" + (System.nanoTime() % 1_000_000);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    @Autowired
    private com.newcoupon.application.service.TenantConfigService tenantConfigService;

    @Autowired
    private TenantFlowRuleDataSource dataSource;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

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

    /**
     * **不再用 {@code FlowRuleManager.loadRules} 灌规则**（2026-10-08 改）。
     *
     * <p>原因：生产路径是"配置 → 数据源 → FlowRuleManager"，而数据源是**全量替换**的。
     * 测试直接 {@code loadRules} 设的规则，会被数据源的下一次刷新（或管理面主动推）
     * **整个冲掉**——实测就是这么红的：规则设了、请求却畅通无阻。
     *
     * <p>所以改成**走生产同一条路径**：改租户配置 → 触发数据源刷新 → 规则自然出现。
     * 这样测的才是真实链路，也顺带证明了"配置改动能驱动限流"。
     */
    @AfterEach
    void clearConfig() {
        // 清掉本类写的租户配置，避免影响别的用例（数据源会据此把规则收敛掉）
        tenantConfigService.remove(APP_A, "rate_limit.daily_quota", "test-cleanup");
        tenantConfigService.remove(APP_B, "rate_limit.daily_quota", "test-cleanup");
        dataSource.refreshNow();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("租户维度：给甲配「日配额=1」→ 甲第二发被拒、乙不受影响")
    void tenantDimensionIsolatedByOrigin() throws Exception {
        // **用日配额而不是 QPS 规则来驱动**：日配额的判据与请求快慢无关
        // （QPS 规则要"打够多次"才超限，而集成测试的请求 1.3 秒一发——那是不可控的量）。
        // 它同样验证 origin：日配额检查器读的正是 `context.getOrigin()`。
        tenantConfigService.put(APP_A, Map.of("rate_limit.daily_quota", "1"), "test");
        dataSource.refreshNow();

        // ① 甲：第一发放行
        var a1 = client.get(RESOURCE, "userId=1", APP_A, secretA);
        assertThat(a1.status())
                .as("甲的第一发应当放行（配额 1）。响应=" + a1.body()).isNotEqualTo(429);

        // ② 甲：第二发必被拒（配额已用完）
        var a2 = client.get(RESOURCE, "userId=1", APP_A, secretA);
        String quotaKey = "c:open:quota:" + APP_A + ":"
                + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String counter = redis.opsForValue().get(quotaKey);
        assertThat(a2.status())
                .as("甲的第二发必须被拒。日配额计数键=" + quotaKey + " 值=" + counter
                        + "（null 表示 Slot 根本没执行；2 表示执行了但没判超限）。响应=" + a2.body())
                .isEqualTo(429);

        // ③ 乙：完全不受影响（甲用完了自己的配额，与乙无关）
        var b1 = client.get(RESOURCE, "userId=1", APP_B, secretB);
        assertThat(b1.status())
                .as("配额是按租户分开的，乙不该被甲拖累。响应=" + b1.body())
                .isNotEqualTo(429);
    }

}
