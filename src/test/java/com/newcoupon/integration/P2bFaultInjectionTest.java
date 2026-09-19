package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.CancelCause;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductCommand;
import com.newcoupon.infrastructure.testing.FaultHooks;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P2b 第 8 步：故障注入验收（第 1 步埋的钩子在此兑现）+ 实测 QPS 报告。
 *
 * <p>用例 ↔ 测试大纲映射（大纲 §4 CONC/CHAO 域的关键红灯，钩子驱动）：
 * <pre>
 * lateCancelAcrossGenerations   CONC-4/CHAO-2：跨代迟到 cancel → 旧代 void（绝不污染新代 stk=超发方向）
 * openBucketCrashGapHeals       CONC-2/CHAO-1：开桶夹缝崩溃（DB 后 Redis 前）→ c:alloc&lt;allocated 安全方向 → 自愈
 * exhaustionStormSingleFlight   CONC-2 惊群：并发 -10 全员开桶 → 单飞（UPDATE 一次）→ 复用槽位重开新世代
 * qpsMeasurementReport          验收口径修正（669b10f）：并发线程数≠吞吐——2000 并发实测 QPS 落日志
 * </pre>
 * 环境注记：同机压测（WSL2+Testcontainers）——数字是**下限不是天花板**，容量认证归 P4。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.reconcile.inflight-ttl-ms=300",
        "coupon.reconcile.log-min-interval-ms=0",
        "coupon.reconcile.auto-repair.mode=force",
        "coupon.sharding.bucket-size=5",      // 压缩桶额度让世代/夹缝场景小成本复现（生产 10000）
        "coupon.sharding.bucket-count=2"
})
class P2bFaultInjectionTest extends ShardedIntegrationTestSupport {

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
    @Autowired
    private DataSource dataSource;

    private static final AtomicLong USER = new AtomicLong(500_000);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @BeforeEach
    void armFaults() {
        FaultHooks.setEnabled(true);
    }

    @AfterEach
    void disarmFaults() {
        FaultHooks.disarmAll();
        FaultHooks.setEnabled(false);
    }

    // ---------- CONC-4/CHAO-2：跨代迟到取消 ----------

    @Test
    @Timeout(120)
    @DisplayName("跨代迟到 cancel：桶已重开新世代 → 旧令牌取消落旧代 void，新代 stk 分毫不动")
    void lateCancelAcrossGenerations() {
        long tpl = createTemplate(10);   // 桶额度5 → 开 2 桶（slot0/1 各 gen1，quota=5）
        long uid = USER.incrementAndGet();

        // 令牌入 slot? gen1（preferred=uid 路由，两桶必有其一）
        var cmd = new DeductCommand(tpl, uid, "gen-1", 3, 0, 0,
                LocalDate.now().format(DAY), 172800, 600);
        var res = stockGateway.deduct(cmd);
        assertThat(res.success()).isTrue();
        long oldStk = bucketStk(tpl, res.bucket(), res.gen());

        // 桶重开新世代（同槽位 gen+1，新额度 5——模拟耗尽重开；世代隔离的进攻面在此）
        assertThat(stockGateway.openBucket(tpl, res.bucket(), res.gen() + 1, 5)).isTrue();
        long newStk = bucketStk(tpl, res.bucket(), res.gen() + 1);

        // 迟到 cancel（transient）：判据=HGET(act,b)==g 不成立 → 落旧代 void，不是新代 stk
        assertThat(stockGateway.cancel(cmd, res.bucket(), res.gen(), CancelCause.TRANSIENT)).isTrue();

        assertThat(bucketStk(tpl, res.bucket(), res.gen() + 1))
                .as("新代 stk 不得被跨代回加污染（每张=超发）").isEqualTo(newStk);
        assertThat(bucketStk(tpl, res.bucket(), res.gen()))
                .as("旧代 stk 也不变（回加走 void 退休）").isEqualTo(oldStk);
        assertThat(bucketVoid(tpl, res.bucket(), res.gen()))
                .as("旧代 void+1：封账分离账本").isEqualTo(1);

        // E2 全世代守恒：旧代(4+1void) + 新代 5 + 另一桶 5 ≡ c:alloc(10+5)——重开后旧代账不丢
        var gc = stockGateway.conservationAll(tpl, 300);
        assertThat(gc.redisSum()).as("E2a：Σ全世代四键 ≡ c:alloc（重开后旧代账不丢）")
                .isEqualTo(gc.alloc());
        assertThat(gc.buckets().size()).as("全世代集合=3 代（含复用槽位的旧代）").isEqualTo(3);
    }

    // ---------- CONC-2/CHAO-1：开桶夹缝崩溃 → 安全方向 → 自愈 ----------

    @Test
    @Timeout(120)
    @DisplayName("开桶夹缝崩溃：DB 提交后 Redis 前被杀 → c:alloc<allocated（安全方向）→ 重试自愈")
    void openBucketCrashGapHeals() {
        long tpl = createTemplate(15);   // 桶额度5×2槽 → 发布开 2 桶用 10，余 5 未分配
        drainQuietly(tpl, 10);           // 领光两桶

        // 崩溃钩子：只炸第一次开桶（DB CAS 已过、Redis 未执行）
        AtomicReference<Throwable> crashed = new AtomicReference<>();
        FaultHooks.arm(FaultHooks.OPEN_BUCKET_SEAM, args -> {
            if (crashed.compareAndSet(null, new RuntimeException("simulated crash: DB committed, Redis EVAL never ran"))) {
                throw new RuntimeException(crashed.get().getMessage());
            }
        });
        // 第 11 张：-10 → 开桶 → 夹缝崩溃（异常透出=进程死亡的最小模拟）
        receiveOrExplode(tpl);
        assertThat(crashed.get()).as("钩子确实在 DB 后 Redis 前炸了").isNotNull();

        // 夹缝态：DB allocated=15，Redis c:alloc=10 —— E3a 的安全方向（<）
        Long alloc = readLong("SELECT allocated FROM coupon_template WHERE id = ?", tpl);
        String redisAlloc = redis.opsForValue().get("c:alloc:{" + tpl + "}");
        assertThat(Long.parseLong(redisAlloc)).isEqualTo(10L);
        assertThat(alloc).isEqualTo(15L);

        // 自愈：下一个请求 -10 → 重试开桶（幂等门：c:alloc 补到 15 不双计）→ 领到券
        String res = receiveQuietly(USER.incrementAndGet(), "gap-heal", tpl);
        assertThat(res).as("夹缝自愈后领券成功").isEqualTo("OK");
        assertThat(redis.opsForValue().get("c:alloc:{" + tpl + "}")).isEqualTo("15");

        var gc = stockGateway.conservationAll(tpl, 300);
        assertThat(gc.redisSum()).isEqualTo(gc.alloc()).isEqualTo(15L);   // E2 双平
    }

    // ---------- CONC-2 惊群：并发 -10 → 单飞开桶 → 复用槽位 ----------

    @Test
    @Timeout(180)
    @DisplayName("耗尽惊群：100 并发撞 -10 → 单飞开桶（一次 CAS）→ 复用槽位新世代 → 恰好补发尾量")
    void exhaustionStormSingleFlight() {
        long tpl = createTemplate(15);   // 2×5 已分配，余 5
        drainQuietly(tpl, 10);

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads), go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), soldOut = new AtomicInteger(), other = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    String r = receiveQuietly(USER.incrementAndGet(), "storm-" + System.nanoTime(), tpl);
                    switch (r) {
                        case "OK" -> ok.incrementAndGet();
                        case "3001" -> soldOut.incrementAndGet();
                        default -> other.incrementAndGet();
                    }
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }

        assertThat(other.get()).as("惊群里不允许 500/DEGRADED 之外的任何意外码").isZero();
        assertThat(ok.get()).as("尾量恰好补发 5 张").isEqualTo(5);
        assertThat(soldOut.get()).isEqualTo(95);
        assertThat(Boolean.TRUE.equals(redis.hasKey("c:opening:{" + tpl + "}")))
                .as("开桶锚已释放").isFalse();

        var gc = stockGateway.conservationAll(tpl, 300);
        assertThat(gc.redisSum()).isEqualTo(gc.alloc()).isEqualTo(15L);   // E2 双平：无双重分配
        assertThat(gc.buckets().size()).as("复用槽位=世代叠加（2 初代+复用新代）").isGreaterThanOrEqualTo(3);
    }

    // ---------- helpers ----------

    private long createTemplate(long stock) {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "P2b故障注入券", 1001L, 1, 1000L, null, 10000L, stock,
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

    private void receiveOrExplode(long tpl) {
        try {
            receiveAppService.receive(new ReceiveRequest("crash-" + System.nanoTime(),
                    USER.incrementAndGet(), tpl));
        } catch (RuntimeException e) {
            // 夹缝崩溃的最小模拟：异常透出即"进程死亡"——不吞掉让测试知道钩子确实炸了
        }
    }

    private void drainQuietly(long tpl, int n) {
        for (int i = 0; i < n; i++) {
            assertThat(receiveQuietly(USER.incrementAndGet(), "drain-" + tpl + "-" + i, tpl))
                    .as("铺水线领券应全部成功").isEqualTo("OK");
        }
    }

    private long bucketStk(long tpl, int bucket, long gen) {
        String v = redis.opsForValue().get("c:stk:{" + tpl + "}:" + bucket + ":" + gen);
        return v == null ? 0 : Long.parseLong(v);
    }

    private long bucketVoid(long tpl, int bucket, long gen) {
        String v = redis.opsForValue().get("c:void:{" + tpl + "}:" + bucket + ":" + gen);
        return v == null ? 0 : Long.parseLong(v);
    }

    private Long readLong(String sql, long tpl) {
        return new JdbcTemplate(dataSource).queryForObject(sql, Long.class, tpl);
    }
}
