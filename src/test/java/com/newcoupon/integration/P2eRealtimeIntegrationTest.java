package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 2e 实时性三件套的集成验证（用户质询"大厂不可忍受分钟级修复"的收口）。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.reconcile.inflight-ttl-ms=300",
        "coupon.reconcile.log-min-interval-ms=0",
        "coupon.reconcile.auto-repair.mode=force",
        "coupon.sharding.bucket-size=10",
        "coupon.sharding.bucket-count=2"
})
class P2eRealtimeIntegrationTest extends ShardedIntegrationTestSupport {

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
    private RedisStockGateway stockGateway;
    @Autowired
    private com.newcoupon.application.service.StockReconcileService reconcileService;
    @Autowired
    private StringRedisTemplate redis;

    private static final AtomicLong USER = new AtomicLong(950_000);

    @Test
    @Timeout(120)
    @DisplayName("售罄牌短TTL：立牌后 10 秒自动放行（清除消息丢失不永久假死）")
    void soldoutFlag_expiresAfter10s() throws InterruptedException {
        long tpl = createTemplate(10);
        for (int i = 0; i < 10; i++) { receiveQuietly(USER.incrementAndGet(), "so-ttl-" + i, tpl); }
        assertThat(receiveQuietly(USER.incrementAndGet(), "so-ttl-x", tpl)).isEqualTo("3001");
        // Redis 排干路径不立牌（只有 DB 拒绝分类才立）——手动按 CANCEL sold_out 的真实形态立牌：
        redis.opsForValue().set("c:so:{" + tpl + "}", "1", java.time.Duration.ofSeconds(10));
        assertThat(stockGateway.isSoldout(tpl)).isTrue();

        // DB 其实没分完额度？——这个场景 total=10=allocated，是"真售罄"
        // 测 TTL：牌存在（但不续期——没有新请求打进来），12 秒后自动消失
        Thread.sleep(12_000);
        assertThat(stockGateway.isSoldout(tpl)).as("清除消息丢失场景：牌 10 秒后自动放行").isFalse();
    }

    @Test
    @Timeout(120)
    @DisplayName("假售罄当场修：伪造部分排干（DB 有余量）→ 下一请求零感知修复并领到券")
    void falseSellout_rescuedInRequest() {
        long tpl = createTemplate(30);   // 2×10=20 分到桶（余 10 未分）
        // 手工把两个桶的 stk 都改成 0（伪造"排干"——DB 的 used 并没到 quota）
        var act = redis.<String, String>opsForHash().entries("c:act:{" + tpl + "}");
        for (var e : act.entrySet()) {
            redis.opsForValue().set("c:stk:{" + tpl + "}:" + e.getKey() + ":" + e.getValue(), "0");
        }
        // 立售罄牌（模拟旧清除协议失败后残留）——注意：急救路径检查"无牌才修"
        // 这里不立牌，让 -3 走"无牌"分支
        redis.delete("c:so:{" + tpl + "}");

        // 下一请求：-3（stk 全 0）→ 无牌 → DB 说 allocated(20)<total(30) → 补开重试 → OK
        String r = receiveQuietly(USER.incrementAndGet(), "fs-rescue", tpl);
        assertThat(r).as("假售罄当场修：用户零感知领到券").isEqualTo("OK");
    }

    @Test
    @Timeout(120)
    @DisplayName("冻结实时止血：注入危险方向漂移 → 巡检当场冻结 → 用户领券收 FROZEN")
    void dangerousDrift_freezesInPatrol() {
        long tpl = createTemplate(20);
        receiveQuietly(USER.incrementAndGet(), "fz-warm-" + tpl, tpl);   // 领 1 张热身

        // 注入危险方向：直接 INCRBY stk +5（Redis 比 DB 多钱=超发面）
        var act = redis.<String, String>opsForHash().entries("c:act:{" + tpl + "}");
        var e0 = act.entrySet().iterator().next();
        redis.opsForValue().increment("c:stk:{" + tpl + "}:" + e0.getKey() + ":" + e0.getValue(), 5);

        // 跑巡检：三步确认 → 危险方向 → 当场冻结（不再等第二轮 60 秒）
        reconcileService.checkOne(tpl);

        assertThat(stockGateway.isFrozen(tpl)).as("危险方向漂移：巡检当场冻结").isTrue();
        BizException ex = org.assertj.core.api.Assertions.catchThrowableOfType(() ->
                receiveAppService.receive(new ReceiveRequest("fz-probe-" + tpl, USER.incrementAndGet(), tpl)),
                BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FROZEN);   // 用户收到可重试的冻结码
    }

    private long createTemplate(long stock) {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "2e实时性验证券", 1001L, 1, 1000L, null, 10000L, stock,
                1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                null, null));
        templateAppService.publish(t.id());
        return t.id();
    }

    private String receiveQuietly(long userId, String reqId, long tplId) {
        try {
            receiveAppService.receive(new ReceiveRequest(reqId, userId, tplId));
            return "OK";
        } catch (BizException e) {
            return String.valueOf(e.getErrorCode().getCode());
        }
    }
}
