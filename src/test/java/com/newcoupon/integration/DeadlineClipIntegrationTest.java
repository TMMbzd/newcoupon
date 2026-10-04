package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.context.RequestContext;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S2 第 9 步：时间预算裁剪（RQ-7）——<b>形状用例</b>。
 *
 * <p><b>只断言"因果事实"，绝不断言耗时</b>（可测性视角的结论）：
 * 「快」是没法被可靠断言的量，而「没有发起依赖调用」「没有留下副作用」是**结构事实**。
 * 用耗时证明快，就是在和线程调度、机器快慢赛跑——本项目已经因为"用不可控的量卡边界"返工过三次。
 *
 * <p><b>只用两个可确定断言的锚点</b>：
 * <ul>
 *   <li><b>已过期</b>：构造 {@code deadline = now - 60_000}——减一分钟而不是减一毫秒，
 *       对时钟抖动与调度延迟免疫；</li>
 *   <li><b>无预算</b>：不设上下文（内部面天然如此）。</li>
 * </ul>
 * 中间态（"还剩 200 毫秒"）在集成层**无法精确断言**，那部分下沉为纯函数单测。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="
})
class DeadlineClipIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long USER_ID = 66_001L;

    @Autowired
    private ReceiveAppService receiveAppService;

    @Autowired
    private OpenAppService openAppService;

    @Autowired
    private TemplateAppService templateAppService;

    @Autowired
    private com.newcoupon.domain.repository.UserCouponRepository userCouponRepository;

    private String appId;
    private Long templateId;

    @BeforeEach
    void setUp() {
        int n = SEQ.incrementAndGet();
        appId = "t-ddl" + n;
        openAppService.create(new CreateAppRequest(appId, "预算裁剪验证", null, null, null));
        templateId = createPublishedTemplate("预算裁剪券" + n, appId);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("预算已耗尽 → 抛专属可重试码（5003，与 5000/5001 可区分），且**不留下任何副作用**")
    void expiredBudget_rejectsBeforeTouchingAnything() {
        // 锚点一：已过期（减一分钟，不靠"减一毫秒"去和调度赛跑）
        RequestContext.set(new RequestContext(appId, "test-trace",
                System.currentTimeMillis() - 60_000, true));

        assertThatThrownBy(() -> receiveAppService.receive(
                new ReceiveRequest("ddl-expired-" + SEQ.get(), USER_ID, templateId)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .as("用专属码而不是 5001——后者被熔断与 Redis 故障共用，排障时无法区分")
                .isEqualTo(5003);

        // 因果事实：**什么都没发生**——这个用户在应用下的券包仍然是空的。
        // 不需要断言"快"："没有产生副作用"本身就是"没有真的干活"的证据。
        assertThat(userCouponRepository.findByUser(USER_ID, null, appId))
                .as("预算耗尽时不该发出任何券").isEmpty();
    }

    @Test
    @DisplayName("对照：无预算（内部面/定时任务）→ 照常领取，裁剪完全不介入")
    void noDeadline_fullFlowUnaffected() {
        RequestContext.clear();   // 锚点二：无预算

        var result = receiveAppService.receive(
                new ReceiveRequest("ddl-nobudget-" + SEQ.get(), USER_ID, templateId));

        assertThat(result.couponId()).as("没有预算的场景不该被裁剪影响").isNotNull();
    }

    @Test
    @DisplayName("预算充足 → 行为与基线一致（裁剪分支不介入）")
    void ampleBudget_behavesLikeBaseline() {
        RequestContext.set(new RequestContext(appId, "test-trace",
                System.currentTimeMillis() + 30_000, true));   // 预算充足

        var result = receiveAppService.receive(
                new ReceiveRequest("ddl-ample-" + SEQ.get(), USER_ID, templateId));

        assertThat(result.couponId()).isNotNull();
    }

    // ------------------------------------------------------------------

    /** 以某个租户的身份建一张已发布的券（归属检查在开放面才生效，测试必须显式模拟） */
    private Long createPublishedTemplate(String name, String ownerAppId) {
        RequestContext.set(new RequestContext(ownerAppId, "test-trace", Long.MAX_VALUE, false));
        try {
            // 参数顺序：name, activityId, couponType, faceValue, discountCap, threshold,
            //          totalCount, perUserLimit, validType, validStart, validEnd, validDays,
            //          dailyLimit, perUserDailyLimit
            var created = templateAppService.create(new TemplateCreateRequest(
                    name, 1001L, 1, 1000L, null, 10000L, 100L, 5, 2, null, null, 7, 0L, 0));
            templateAppService.publish(created.id());
            return created.id();
        } finally {
            RequestContext.clear();
        }
    }
}
