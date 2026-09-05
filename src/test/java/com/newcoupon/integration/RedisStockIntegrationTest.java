package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.StockReconcileService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 阶段 1 验收（ADR-0006 / docs/00 阶段1清单）——Redis+MySQL 双容器：
 * ① 通用限领 N=3：同用户 5 次 → 3 成功 2 超领，seq=1/2/3 落库
 * ② Redis 计数丢失 → DB 序号唯一索引兜底（最后防线证明）
 * ③ 2000 并发抢 1000：零超发 + 对账恒等式 + 漂移重建后余量正确
 * ④ 库存漂移 → 对账重建（kill -9 残余的收口）
 * ⑤ 每人日限=1：第二张拒绝
 * ⑥ 批次日限=1：第二个用户拒绝(3003)
 */
@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=50")
class RedisStockIntegrationTest {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private RedisStockGateway stockGateway;
    @Autowired
    private StockReconcileService reconcileService;

    private static final AtomicInteger USER = new AtomicInteger(80_000);

    private long createTemplate(long stock, Integer perUserLimit, Long dailyLimit, Integer perUserDaily) {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "阶段1验收券", 1001L, 1, 1000L, null, 10000L, stock,
                perUserLimit, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                dailyLimit, perUserDaily));
        templateAppService.publish(t.id());
        return t.id();
    }

    private record Res(ReceiveResult result, String code) {
    }

    private Res receive(long userId, String reqId, long tplId) {
        try {
            return new Res(receiveAppService.receive(new ReceiveRequest(reqId, userId, tplId)), "OK");
        } catch (BizException e) {
            return new Res(null, String.valueOf(e.getErrorCode().getCode()));
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("验收①：通用限领 N=3——同用户5次(不同requestId) → 3成功2超领")
    void generalLimitN() {
        long tplId = createTemplate(100, 3, null, null);
        long uid = USER.incrementAndGet();

        assertThat(receive(uid, "n-1", tplId).code()).isEqualTo("OK");
        assertThat(receive(uid, "n-2", tplId).code()).isEqualTo("OK");
        assertThat(receive(uid, "n-3", tplId).code()).isEqualTo("OK");
        assertThat(receive(uid, "n-4", tplId).code()).isEqualTo("3002");
        assertThat(receive(uid, "n-5", tplId).code()).isEqualTo("3002");

        assertThat(templateRepository.findById(tplId).orElseThrow().getIssuedCount())
                .as("超领的两次不产生扣账（Redis闸门在扣库存前拦截）").isEqualTo(3);
    }

    @Test
    @Timeout(120)
    @DisplayName("验收②：Redis用户计数丢失 → 放行seq=1撞uk_user_seq → 3002（DB最后防线）")
    void seqFallbackWhenRedisCountLost() {
        long tplId = createTemplate(100, 2, null, null);
        long uid = USER.incrementAndGet();
        assertThat(receive(uid, "s-1", tplId).code()).isEqualTo("OK");
        assertThat(receive(uid, "s-2", tplId).code()).isEqualTo("OK");

        stockGateway.resetUserCount(tplId, uid);   // 模拟 Redis 计数丢失（重启/主从切换）

        Res third = receive(uid, "s-3", tplId);
        assertThat(third.code()).as("Redis 已放行，DB 序号唯一索引仍挡住超领").isEqualTo("3002");
        assertThat(templateRepository.findById(tplId).orElseThrow().getIssuedCount()).isEqualTo(2);
    }

    @Test
    @Timeout(300)
    @DisplayName("验收③：2000并发抢1000 → 零超发 + 对账恒等式 + 无补偿残留")
    void twoThousandConcurrent() throws Exception {
        long tplId = createTemplate(1000, 1, null, null);
        // 容量配平教训（实测踩坑）：在途并发必须 ≤ 连接池(50)——200线程打50连接池，
        // 排队超过 connection-timeout(3s) 直接 CannotCreateTransactionException。
        // 池=在途事务上限，这是"连接池就是库存"的又一次体现。
        ExecutorService pool = Executors.newFixedThreadPool(50);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Res>> futures = IntStream.range(0, 2000)
                    .mapToObj(i -> pool.submit(() -> {
                        gate.await();
                        return receive(90_000L + i, "bulk-" + i, tplId);
                    }))
                    .toList();
            gate.countDown();
            List<Res> results = futures.stream()
                    .map(f -> { try { return f.get(120, TimeUnit.SECONDS); }
                                catch (Exception e) { throw new IllegalStateException(e); } })
                    .toList();

            long ok = results.stream().filter(r -> "OK".equals(r.code())).count();
            var failDist = results.stream().filter(r -> !"OK".equals(r.code()))
                    .collect(java.util.stream.Collectors.groupingBy(Res::code,
                            java.util.stream.Collectors.counting()));
            assertThat(ok).as("成功数恰等于库存").isEqualTo(1000);
            assertThat(failDist).as("失败只有售罄3001").containsOnlyKeys("3001");
            assertThat(failDist.get("3001")).isEqualTo(1000L);

            // 对账恒等式：Redis余量 + DB已发 == 总量；DB issued == 成功数
            CouponTemplateLike tpl = templateOf(tplId);
            assertThat(tpl.issued()).isEqualTo(1000);
            assertThat(stockGateway.remaining(tplId))
                    .as("Redis余量=总量-已发（补偿无残留）").isEqualTo(tpl.total() - 1000);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("验收④：库存漂移（模拟kill-9）→ 对账重建后恢复正确")
    void driftRebuild() {
        long tplId = createTemplate(100, 1, null, null);
        long uid = USER.incrementAndGet();
        receive(uid, "d-1", tplId);   // issued=1, redis=99

        stockGateway.initStock(tplId, 50);   // 人为污染：模拟扣减后进程被杀（漂移）

        assertThat(stockGateway.remaining(tplId)).isEqualTo(50);
        reconcileService.reconcileAll();     // 对账：以 DB 账本为准重建

        assertThat(stockGateway.remaining(tplId))
                .as("重建后 Redis == total - issued").isEqualTo(99);
    }

    @Test
    @Timeout(120)
    @DisplayName("验收⑤：每人日限=1——第二张被 3002 拒绝（提示每日）")
    void perUserDailyLimit() {
        long tplId = createTemplate(100, 5, null, 1);
        long uid = USER.incrementAndGet();
        assertThat(receive(uid, "pd-1", tplId).code()).isEqualTo("OK");
        assertThat(receive(uid, "pd-2", tplId).code()).isEqualTo("3002");
    }

    @Test
    @Timeout(120)
    @DisplayName("验收⑥：批次日限=1——第二个用户 3003")
    void batchDailyLimit() {
        long tplId = createTemplate(100, 1, 1L, null);
        assertThat(receive(USER.incrementAndGet(), "bd-1", tplId).code()).isEqualTo("OK");
        assertThat(receive(USER.incrementAndGet(), "bd-2", tplId).code()).isEqualTo("3003");
    }

    // ---- 小工具：避免领域聚合直接依赖 ----
    private record CouponTemplateLike(long total, long issued) {
    }

    private CouponTemplateLike templateOf(long id) {
        var t = templateRepository.findById(id).orElseThrow();
        return new CouponTemplateLike(t.getTotalCount(), t.getIssuedCount());
    }
}
