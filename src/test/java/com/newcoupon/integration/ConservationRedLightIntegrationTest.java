package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.StockReconcileService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ADR-0009 守恒迁移的红灯（2026-09-16，工序 v2：先证明恒等式的盲区真实存在）。
 *
 * <p>旧恒等式 issued−dbi−pend≡0 只校验 Redis 内部记账自洽，三个盲区（ADR-0009 §7）：
 * ① stk 不在式内——改低/改高永久无检测（少卖/敞口）；② issued/dbi/pend 三键整体平移
 * 恒等式仍平；③ 全清库 0=0 空真。守恒式 stk+pend+dbi+void≡total 全抓。
 *
 * <p>红灯形态：直接污染 Redis（模拟单键错写/清库），断言巡检应确认漂移——
 * 现行 identityCheck 只验恒等式，两个场景都静默 → 红。
 */
@SpringBootTest(properties = {
        // 两轮确认间隔由测试直调控制，这里不需要特殊配置
})
class ConservationRedLightIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private StockReconcileService reconcileService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RedisStockGateway stockGateway;

    private static final AtomicInteger USER_SEQ = new AtomicInteger(96_000);

    /** 建模板并发布，返回模板 id（total=100） */
    private long publishedTemplate(String tag) {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "守恒红灯券", 1005L, 1, 1000L, null, 10000L, 100L, 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, null, null));
        templateAppService.publish(t.id());
        return t.id();
    }

    @Test
    @Timeout(120)
    @DisplayName("红灯①：stk 被改低（恒等式盲区——stk 不在式内）→ 巡检应确认漂移")
    void stkPollutionDetected() {
        long tplId = publishedTemplate("stk");
        // 触发一轮巡检建立基线（首轮 suspicion 清零）
        reconcileService.identityCheckAll();
        // 污染：直接改低 stk（人工错写/部分失效形态）——恒等式 issued−dbi−pend 不受影响
        redis.opsForValue().set("c:stk:{" + tplId + "}", "50");

        reconcileService.identityCheckAll();   // 第一轮：怀疑
        int confirmed = reconcileService.identityCheckAll();   // 第二轮：定罪（两轮确认语义）

        // 守恒式应确认：50+0+0+0 ≠ 100。现状恒等式：0−0−0=0 平 → 静默（红）
        assertThat(confirmed)
                .as("stk 改低 50（total=100）：守恒式应发现并确认漂移").isGreaterThanOrEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("红灯②：全清库（issued/dbi/pend/void 全没了）→ 巡检应确认漂移（恒等式 0=0 空真）")
    void flushAllDetected() {
        long tplId = publishedTemplate("flush");
        // 先真实发放 3 张：dbi=3（限领1张→三个不同用户，各领一张）
        for (int i = 1; i <= 3; i++) {
            ReceiveResult r = receiveAppService.receive(
                    new ReceiveRequest("rl-flush-" + i, USER_SEQ.incrementAndGet() + 0L, tplId));
            assertThat(r.duplicated()).isFalse();
        }
        reconcileService.identityCheckAll();   // 基线
        // 清库形态：状态键全部消失（故障切换丢整库）——恒等式读三个 nil 按 0 算：0−0−0=0 平
        redis.delete(java.util.List.of("c:stk:{" + tplId + "}", "c:dbi:{" + tplId + "}",
                "c:issued:{" + tplId + "}", "c:pend:{" + tplId + "}"));

        reconcileService.identityCheckAll();   // 第一轮：怀疑
        int confirmed = reconcileService.identityCheckAll();   // 第二轮：定罪

        // 守恒式应确认：0+0+0+0 ≠ 100（真实态应是 97+0+3+0）。现状恒等式空真 → 静默（红）
        assertThat(confirmed)
                .as("全清库：守恒式应发现（stk 缺失≠0 张剩余）").isGreaterThanOrEqualTo(1);
    }

}
