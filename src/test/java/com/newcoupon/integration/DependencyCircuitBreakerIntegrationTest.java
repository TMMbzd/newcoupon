package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Map;
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
 * S2 第 7 步：依赖级熔断（RQ-5）——<b>红绿灯</b>。
 *
 * <p>红：本类先于接线写就——熔断器组件已存在，但**业务路径还没用它**，
 * 所以"把熔断器打开"对领券没有任何影响，请求照样走到业务。
 *
 * <p><b>怎么测才可控</b>：不去真的把 Redis 停掉（那会污染同批跑的其它用例），
 * 而是用 Resilience4j 自己的 API 把熔断器**直接置为打开**，然后观察业务路径的行为——
 * 这测的正是"我们的接线对不对"，而不是"Resilience4j 会不会熔断"。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="
})
class DependencyCircuitBreakerIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final long USER_ID = 88_001L;

    @Autowired
    private DependencyCircuitBreakers breakers;

    @Autowired
    private com.newcoupon.application.service.ReceiveAppService receiveAppService;

    @Autowired
    private OpenAppService openAppService;

    @Autowired
    private TemplateAppService templateAppService;

    /**
     * 每个用例**自己准备**数据：一个独有的 appId + 一张已发布的券。
     *
     * <p><b>为什么不用 static 共享</b>：共享状态出过一次查不清的现象——某个用例里
     * {@code templateId} 莫名其妙是 null（而 @BeforeEach 时的守卫又是通过的）。
     * 与其追查"谁改了静态字段"，不如**让用例不依赖共享状态**：
     * 每个用例用自己的数据，"起点干净"由自己保证。这也顺带规避了
     * 另一个未定位的现象（复用 appId 时偶发"已存在"）。
     *
     * <p>代价是每个用例多两次写库（建应用 + 建券发布），本机几毫秒，换来的是**确定性**。
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private String appId;
    private Long templateId;

    @BeforeEach
    void provisionPerTest() {
        appId = "t-cb-" + SEQ.incrementAndGet() + "-" + (System.nanoTime() % 100000);
        openAppService.create(new CreateAppRequest(appId, "熔断验证", null, null, null));

        // 参数顺序：name, activityId, couponType, faceValue, discountCap, threshold,
        //          totalCount, perUserLimit, validType, validStart, validEnd, validDays,
        //          dailyLimit, perUserDailyLimit
        var created = templateAppService.create(new TemplateCreateRequest(
                "熔断券-" + appId, 1001L, 1, 1000L, null, 10000L,
                100L, 5, 2, null, null, 7, 0L, 0));
        templateId = created.id();
        templateAppService.publish(templateId);
    }

    /** 熔断器状态是**进程级**的：一个用例把它打开，下一个用例就受影响 → 每个用例后复位 */
    @AfterEach
    void resetBreakers() {
        breakers.redis().reset();
        breakers.mysql().reset();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Redis 熔断器打开 → 领券快速失败并返回「降级中」（不是等超时，也不是业务错误）")
    void redisBreakerOpen_returnsDegraded() {
        breakers.redis().transitionToOpenState();

        var ex = catchBiz(() -> receiveAppService.receive(request("cb-redis")));

        assertThat(ex).as("熔断打开时应当在闸门处快速失败").isNotNull();
        assertThat(ex.getErrorCode().getCode())
                .as("降级码 5001——可重试语义，而不是业务错误")
                .isEqualTo(5001);
    }

    @Test
    @DisplayName("MySQL 熔断器打开 → 领券快速失败并返回「系统繁忙」")
    void mysqlBreakerOpen_returnsSystemBusy() {
        breakers.mysql().transitionToOpenState();

        var ex = catchBiz(() -> receiveAppService.receive(request("cb-mysql")));

        assertThat(ex).as("熔断打开时应当在落库处快速失败").isNotNull();
        assertThat(ex.getErrorCode().getCode())
                .as("系统码 5000——比降级更重的语义")
                .isEqualTo(5000);
    }

    @Test
    @DisplayName("对照：熔断器正常时，领券照常成功（证明上两条的失败来自熔断，不是别的原因）")
    void breakersClosed_normalFlow() {
        var result = receiveAppService.receive(request("cb-normal"));

        assertThat(result.couponId()).as("正常路径应当拿到券").isNotNull();
    }

    @Test
    @DisplayName("失败累积到阈值 → 熔断器自己打开（证明阈值配置真的生效）")
    void repeatedFailures_openTheBreaker() {
        CircuitBreaker breaker = breakers.redis();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 模拟连续失败：最小样本数配置为 5，异常比例阈值 50%
        for (int i = 0; i < 5; i++) {
            breaker.onError(0, TimeUnit.NANOSECONDS, new RuntimeException("模拟依赖故障"));
        }

        assertThat(breaker.getState())
                .as("5 次连续失败（样本数达阈值）应当让熔断器打开").isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ------------------------------------------------------------------

    private com.newcoupon.application.dto.ReceiveRequest request(String requestId) {
        return new com.newcoupon.application.dto.ReceiveRequest(requestId, USER_ID, templateId);
    }

    private com.newcoupon.common.BizException catchBiz(Runnable action) {
        try {
            action.run();
            return null;
        } catch (com.newcoupon.common.BizException e) {
            return e;
        }
    }
}
