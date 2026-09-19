package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * P2b 第 8 步：实测 QPS 报告（验收口径修正 669b10f：并发线程数≠吞吐）。
 * 独立成类原因：生产参数（大桶）——2000 并发抢 1000 应零开桶，测的是稳态吞吐；
 * 开桶风暴/夹缝/世代等故障形态在 P2bFaultInjectionTest（小桶参数）。
 * 环境注记：WSL2 同机压测（应用+MySQL+Redis+压测线程共享 CPU）——数字是**下限不是天花板**，
 * 容量认证（阶梯加压/拐点/soak/容量评估表）归 P4。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.reconcile.inflight-ttl-ms=300",
        "coupon.reconcile.log-min-interval-ms=0",
        "coupon.sharding.bucket-size=10000",
        "coupon.sharding.bucket-count=16"
})
class P2bQpsReportTest extends ShardedIntegrationTestSupport {

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

    private static final AtomicLong USER = new AtomicLong(700_000);

    @Test
    @Timeout(240)
    @DisplayName("实测 QPS：2000 并发抢 1000（大桶零开桶，稳态吞吐）")
    void qpsMeasurementReport() {
        long tpl = templateAppService.create(new TemplateCreateRequest(
                "P2b吞吐测量券", 1001L, 1, 1000L, null, 10000L, 1000L,
                1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                null, null)).id();
        templateAppService.publish(tpl);

        int threads = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);   // 全额池：半池+全量栅栏=自锁死
        CountDownLatch ready = new CountDownLatch(threads), go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), soldOut = new AtomicInteger();
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    String r = receiveQuietly(USER.incrementAndGet(), "qps-" + System.nanoTime(), tpl);
                    if ("OK".equals(r)) { ok.incrementAndGet(); }
                    else if ("3001".equals(r)) { soldOut.incrementAndGet(); }
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(180, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        double qps = threads / seconds;
        System.out.printf("[P2b-QPS-REPORT] requests=%d, wall=%.2fs, 实测吞吐=%.0f QPS, ok=%d, soldOut=%d, "
                        + "环境=WSL2同机(下限非天花板, 容量认证归P4)%n",
                threads, seconds, qps, ok.get(), soldOut.get());

        assertThat(ok.get()).as("成功数恰等于库存").isEqualTo(1000);
        assertThat(soldOut.get()).isEqualTo(1000);
        var gc = stockGateway.conservationAll(tpl, 300);
        assertThat(gc.redisSum()).isEqualTo(gc.alloc()).isEqualTo(1000L);
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
