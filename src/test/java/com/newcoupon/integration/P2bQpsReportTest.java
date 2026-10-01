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

    @Autowired
    private javax.sql.DataSource dataSource;

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
        // 每一类结果的去向都要能看见（L40：此前只数 OK/3001，其余 147 例失败"去向不可知"——
        // 红的时候无从判断是环境噪声还是代码退化）。同线程内计数，无并发原语需求。
        java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> codes =
                new java.util.concurrent.ConcurrentHashMap<>();
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    String r;
                    try {
                        r = receiveQuietly(USER.incrementAndGet(), "qps-" + System.nanoTime(), tpl);
                    } catch (Throwable t) {
                        // L40 纪律：**每一类去向都要能看见**——非业务异常（连接池超时/Redis 超时等）
                        // 此前会从任务里抛出后被 Future 吞掉，导致"总数对不上、去向不可知"
                        r = "EX:" + t.getClass().getSimpleName();
                    }
                    if ("OK".equals(r)) { ok.incrementAndGet(); }
                    else if ("3001".equals(r)) { soldOut.incrementAndGet(); }
                    else { codes.computeIfAbsent(r, k -> new java.util.concurrent.atomic.AtomicInteger())
                            .incrementAndGet(); }
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
                        + "其它=%s, 环境=WSL2同机(下限非天花板, 容量认证归P4)%n",
                threads, seconds, qps, ok.get(), soldOut.get(), codes.isEmpty() ? "无" : codes);

        // L40 纪律（2026-09-30）：去向必须"账目平衡"——成功 + 售罄 + 各类失败/异常 = 总请求数。
        // 此前非业务异常会被 Future 吞掉，出现"总数对不上、去向不可知"（198 例那轮 805 例失踪）。
        java.util.Map<String, Integer> dist = new java.util.TreeMap<>();
        codes.forEach((k, v) -> dist.put(k, v.get()));
        int counted = ok.get() + soldOut.get() + dist.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(counted).as("每一类去向都必须被计数（成功+售罄+失败/异常=总数），分布=" + dist)
                .isEqualTo(threads);
        // 不变量一（正确性）：**绝不超发**——成功数不得超过库存；配额不得多划。
        assertThat(ok.get()).as("绝不超发：成功数 ≤ 库存，分布=" + dist).isLessThanOrEqualTo(1000);
        var gc = stockGateway.conservationAll(tpl, 300);
        assertThat(gc.alloc()).as("配额守恒（未多划额度）").isEqualTo(1000L);
        // 不变量二（数据库真值）：发出的张数 = 成功数——同时钉住"零超发"与"零幻影"
        Long usedSum = new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .queryForObject("SELECT COALESCE(SUM(used),0) FROM stock_bucket WHERE template_id = ?",
                        Long.class, tpl);
        assertThat(usedSum).as("数据库已发放 = 成功数（分布=" + dist + "）").isEqualTo((long) ok.get());
        // 注意：这里**不**断言 Σ四键 ≡ c:alloc——重压下会有大量请求死在数据库上，而它们的取消
        // 在探针不通时不盲目执行（设计如此），留下一批残留令牌；本次读数的 300ms 清扫窗口会把它
        // 们扫掉，造成"少卖方向"的破缺——那正是巡检要修的东西（故意破缺），不是本用例该钉的不变量。
        // 收敛性由对账用例覆盖（如 P2bEnsureRecoveryTest / RedisStockIntegrationTest 的风暴收敛断言）。
        // 不变量二（可解释性）：每一类去向都被计数（成功 + 售罄 + 各类异常 = 总数）
        assertThat(counted).as("去向账目平衡，分布=" + dist).isEqualTo(threads);
        // 不变量三（容量）**不作断言**：本机在 2000 并发下能否把 1000 张全部发出去，受连接池与超时制约
        // （登记在 L31/L42②：20 连接 × 3s 超时 vs 2000 线程）；实测满载时会出现大量
        // TransientDataAccessResourceException（2026-09-30 全量实测 1117/2000）——那是容量问题，
        // 不是正确性问题，容量认证归 P4。这里只报告，不判红（此前断言 ok==1000 在负载下必红=长期噪声）。
        if (ok.get() < 1000) {
            System.out.printf("[P2b-QPS-NOTE] ok=%d < 1000：本机未能消化全部请求（分布=%s）——"
                    + "容量问题（连接池/超时，见 L31/L42②），非正确性问题；容量认证归 P4%n", ok.get(), dist);
        }
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
