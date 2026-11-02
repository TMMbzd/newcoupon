package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.GateCounterRepairService;
import com.newcoupon.application.service.ReceiveAsyncAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 修复风暴：同一个模板的 gate 损坏时，N 个并发受理只能有一个请求做数据库重建，
 * 其他请求快速返回 5001，不能出现 N 次 repairAll。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.stock.shard-count=8",
        "coupon.issue.async-enabled=true",
        "coupon.issue.async-template-ids=*",
        "coupon.issue.worker.enabled=true",
        "coupon.issue.worker.initial-delay-ms=3600000",
        "coupon.issue.worker.fixed-delay-ms=3600000",
        "coupon.issue.gate-compensation.initial-delay-ms=3600000",
        "coupon.issue.gate-compensation.fixed-delay-ms=3600000",
        "coupon.issue.gate-repair.initial-delay-ms=3600000",
        "coupon.issue.gate-repair.fixed-delay-ms=3600000",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class GateRepairStormIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger USER = new AtomicInteger(910_000);

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAsyncAppService receiveAsyncAppService;
    @Autowired
    private CouponStockShardMapper shardMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;
    @SpyBean
    private GateCounterRepairService counterRepairService;

    private long templateId;

    @AfterEach
    void cleanup() {
        if (templateId > 0) {
            jdbc.update("DELETE FROM coupon_issue_task WHERE template_id = ?", templateId);
            jdbc.update("DELETE FROM coupon_user_daily_counter WHERE template_id = ?", templateId);
            jdbc.update("DELETE FROM coupon_daily_counter WHERE template_id = ?", templateId);
            redis.delete("c:issue:gate:{" + templateId + "}");
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("gate 损坏时 30 个并发受理只触发一次 repairAll，其他请求快速失败或成功")
    void concurrentRepairRunsOnce() throws Exception {
        templateId = createTemplate(50, 5);
        redis.opsForValue().set("c:issue:gate:{" + templateId + "}", "not-a-number");
        clearInvocations(counterRepairService);

        int concurrency = 30;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < concurrency; i++) {
            long userId = USER.incrementAndGet();
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                    receiveAsyncAppService.receive(new ReceiveRequest(
                            "storm-" + userId, userId, templateId));
                    results.add("OK");
                } catch (BizException e) {
                    results.add(String.valueOf(e.getErrorCode().getCode()));
                } catch (Exception e) {
                    results.add("EX:" + e.getClass().getSimpleName());
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(results.stream().filter("OK"::equals).count()).isGreaterThan(0);
        assertThat(results).allMatch(r -> r.equals("OK") || r.equals("5001"));
        verify(counterRepairService, times(1)).repairAll(any());
        assertThat(shardMapper.sumUsed(templateId)).isLessThanOrEqualTo(50);
        assertThat(issueGateValue()).isNotNull();
    }

    private String issueGateValue() {
        return redis.opsForValue().get("c:issue:gate:{" + templateId + "}");
    }

    private long createTemplate(long total, int perUserLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "修复风暴验证券", 1001L, 1, 1000L, null, 10000L, total, perUserLimit,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }
}
