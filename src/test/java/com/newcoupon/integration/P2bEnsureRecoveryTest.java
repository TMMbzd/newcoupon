package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * P2b ENSURE 灾后重建的端到端覆盖（2026-09-21 补）。
 *
 * <p>为什么补这个：ensureBuckets 曾带"三元组/四元组失配"的运行时 Bug 存活多日——原因正是
 * -7 重建路径只有 mock 单测、没有任何集成测试真正引爆过它。本类模拟"Redis 整库丢失"
 * （删桶键+花名册+镜像），验证下一个领券请求触发 ENSURE 按 DB 行推导重建（stk=quota−used，
 * 不虚增），且用户无感领到券。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.reconcile.inflight-ttl-ms=300",
        "coupon.reconcile.log-min-interval-ms=0",
        "coupon.sharding.bucket-size=10",
        "coupon.sharding.bucket-count=2"
})
class P2bEnsureRecoveryTest extends ShardedIntegrationTestSupport {

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
    private StringRedisTemplate redis;

    private static final AtomicLong USER = new AtomicLong(600_000);

    @Test
    @Timeout(120)
    @DisplayName("Redis 整库丢失 → 下一请求触发 ENSURE 按 DB 行推导重建 → 领券成功且账不虚增")
    void redisLoss_healsByEnsure_nextReceiveSucceeds() {
        // 铺垫：20 张模板（桶额 10×2 槽），先真实领 3 张
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "ENSURE重建验证券", 1001L, 1, 1000L, null, 10000L, 20L,
                1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                null, null));
        templateAppService.publish(t.id());
        for (int i = 0; i < 3; i++) {
            String r = receiveQuietly(USER.incrementAndGet(), "ens-" + t.id() + "-" + i, t.id());
            assertThat(r).as("铺垫领券应成功").isEqualTo("OK");
        }

        // 灾难：删光所有桶键+花名册+全世代名册+镜像（Redis 对这个模板彻底失忆）
        List<String> doomed = new ArrayList<>();
        doomed.add("c:act:{" + t.id() + "}");
        doomed.add("c:gens:{" + t.id() + "}");
        doomed.add("c:alloc:{" + t.id() + "}");
        for (var e : redis.<String, String>opsForHash().entries("c:act:{" + t.id() + "}").entrySet()) {
            String base = "{" + t.id() + "}:" + e.getKey() + ":" + e.getValue();
            doomed.add("c:stk:" + base);
            doomed.add("c:dbi:" + base);
            doomed.add("c:void:" + base);
            doomed.add("c:pend:" + base);
        }
        redis.delete(doomed);
        assertThat(redis.opsForValue().get("c:act:{" + t.id() + "}")).isNull();

        // 第 4 个用户领券：-7 → 退避 → ENSURE 按 DB 行重建（stk=quota−used 推导）→ 重扣成功
        String r = receiveQuietly(USER.incrementAndGet(), "ens-heal", t.id());
        assertThat(r).as("Redis 失忆后下一请求应无感恢复").isEqualTo("OK");

        // 账验：重建后 E2 双平且不虚增——Σ四键=镜像=20（已领 3+1=4 张在 dbi/镜像侧如实体现）
        var gc = stockGateway.conservationAll(t.id(), 300);
        assertThat(gc.redisSum()).as("E2a：Σ全世代四键 ≡ c:alloc").isEqualTo(gc.alloc());
        assertThat(gc.alloc()).as("E2b：镜像 ≡ Σquota=20").isEqualTo(20L);
        long dbiSum = gc.buckets().stream().mapToLong(b -> b.dbi()).sum();
        assertThat(dbiSum).as("重建后 dbi=已领 4 张（按 used 推导铸账，不虚增不清零）").isEqualTo(4L);
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
