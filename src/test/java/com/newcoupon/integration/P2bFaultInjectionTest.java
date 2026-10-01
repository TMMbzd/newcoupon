package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.StockBucketRouter;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private StockBucketRouter stockBucketRouter;
    @Autowired
    private RedisStockGateway stockGateway;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private com.newcoupon.application.service.StockReconcileService reconcileService;

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
        assertThat(stockGateway.openBucket(tpl, res.bucket(), res.gen() + 1, 5)).isEqualTo(1);
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

    // ---------- 大盘模板批量重开（修"全桶耗尽后单桶补=单行热点"，2026-09-21） ----------

    @Test
    @Timeout(180)
    @DisplayName("批量重开：25张/2槽/桶额5——每波耗尽后两槽同时换代（非一次一只），尾量5收官")
    void megaTemplate_batchRefill_keepsParallelism() {
        long tpl = createTemplate(25);   // 本类 props 桶额5×2槽：发布开 2 桶（5+5=10），余 15 未分配
        // route() 是公开纯函数——离线按桶挑用户，精确 5:5 排空两桶且不提前触发 -10
        // （连续用户 hash 不均会让后段提前撞 -10；perUserLimit=1，每波都要新用户）
        java.util.List<Long> b0 = new java.util.ArrayList<>(), b1 = new java.util.ArrayList<>();
        for (long uid = 1; uid <= 200_000 && (b0.size() < 15 || b1.size() < 15); uid++) {
            int b = StockBucketRouter.route(uid, 2);
            if (b == 0 && b0.size() < 15) { b0.add(uid); }
            if (b == 1 && b1.size() < 15) { b1.add(uid); }
        }
        int seq = 0;
        for (int i = 0; i < 5; i++) { assertThat(receiveQuietly(b0.get(i), "m0-" + seq++, tpl)).isEqualTo("OK"); }
        for (int i = 0; i < 5; i++) { assertThat(receiveQuietly(b1.get(i), "m1-" + seq++, tpl)).isEqualTo("OK"); }
        var act1 = redis.<String, String>opsForHash().entries("c:act:{" + tpl + "}");
        assertThat(act1).containsEntry("0", "1").containsEntry("1", "1");   // 初代确认（未触发重开）

        // 第 11 张：-10 → 批量重开应把两个槽**都**推到第 2 代（一次一只的旧实现只推槽 0）
        assertThat(receiveQuietly(b0.get(5), "m-wave2", tpl)).isEqualTo("OK");
        var act2 = redis.<String, String>opsForHash().entries("c:act:{" + tpl + "}");
        assertThat(act2).as("批量重开：两槽同波换代（gen 都=2），并行度恢复而非单行热点")
                .containsEntry("0", "2").containsEntry("1", "2");

        // 第二波排干：重开额度 5+5，已用 1（m-wave2），剩 9——桶0 用户 5 个 + 桶1 用户 4 个
        for (int i = 6; i < 11; i++) { assertThat(receiveQuietly(b0.get(i), "w2-0-" + i, tpl)).isEqualTo("OK"); }
        for (int i = 6; i < 10; i++) { assertThat(receiveQuietly(b1.get(i), "w2-1-" + i, tpl)).isEqualTo("OK"); }
        // 全部排干后的下一张：尾量 5 → 批量重开只开槽 0 第 3 代（额度分到只剩 5）
        assertThat(receiveQuietly(b1.get(11), "m-wave3", tpl)).isEqualTo("OK");
        var act3 = redis.<String, String>opsForHash().entries("c:act:{" + tpl + "}");
        assertThat(act3.get("0")).as("第三波：尾量5落在槽0第3代").isEqualTo("3");

        // 尾量剩 4：排干后第 26 张真售罄
        for (int i = 12; i < 15; i++) { assertThat(receiveQuietly(b0.get(i), "w3-" + i, tpl)).isEqualTo("OK"); }
        assertThat(receiveQuietly(b1.get(12), "w3-last", tpl)).isEqualTo("OK");
        assertThat(receiveQuietly(b1.get(13), "m-soldout", tpl)).isEqualTo("3001");   // 25 张发完

        var gc = stockGateway.conservationAll(tpl, 300);
        assertThat(gc.redisSum()).as("E2a 全程双平").isEqualTo(gc.alloc()).isEqualTo(25L);
    }

    // ---------- L37：开桶锁过期后恢复写入——同一段额度不得重复建行 ----------

    /**
     * L37 用例（docs/L37-开桶锁过期后的重复建行-复现与技术方案-2026-09-27.md）：
     * 开桶人 A 在"额度已加进 allocated、桶行还没插"之间被暂停，暂停时长超过开桶锁的
     * 有效期（5 秒）——来源可以是长 GC、数据库阻塞、虚拟机挂起。锁被请求 B 接管，
     * B 把这笔"已划出、还没建行"的额度补建了一行。A 恢复后继续执行、又要插一行。
     * 修复前：同一段额度建了两行，多出来的额度可以真实卖出去（超发）。
     * 修复后：A 的插行被"同一段额度只允许一行"的唯一键挡下，Σquota 恒等于 allocated。
     */
    @Test
    @Timeout(120)
    @DisplayName("L37 锁过期后恢复写入：同一段额度不得重复建行（Σquota ≡ allocated）")
    void suspendedOpenerDoubleInsertIsBlocked() throws Exception {
        long tpl = createTemplate(15);   // 2 槽 × 5 张（本类 props：桶额 5）
        drainQuietly(tpl, 10);           // 排空两桶：allocated=10、Σquota=10

        // 暂停钩子：只在第一次点火时睡（开桶人 A 走到"额度已加、行未插"的位置）
        AtomicBoolean slept = new AtomicBoolean(false);
        CountDownLatch atSeam = new CountDownLatch(1);
        FaultHooks.arm(FaultHooks.OPEN_BUCKET_SEAM, args -> {
            if (slept.compareAndSet(false, true)) {
                atSeam.countDown();
                try {
                    Thread.sleep(7_000);   // > 锁的有效期(5s)：A 的锁在睡眠期间过期
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread opener = new Thread(() -> stockBucketRouter.refillExhaustedBuckets(tpl), "suspended-opener");
        opener.start();
        assertThat(atSeam.await(10, TimeUnit.SECONDS))
                .as("A 已停在缝里（额度已加进 allocated、桶行还没插）").isTrue();

        // B：正常入口路径（-10 → refill 先一步返回 false（allocated>=total）→ tryOpenBucket 差额恢复）。
        // 重试直到抢到 A 已过期的锁——复刻真实调用方的重试行为，不依赖固定睡眠的时序巧合。
        boolean opened = false;
        long deadline = System.currentTimeMillis() + 10_000;
        while (!opened && System.currentTimeMillis() < deadline) {
            opened = stockBucketRouter.refillExhaustedBuckets(tpl)
                    || stockBucketRouter.tryOpenBucket(tpl);
            if (!opened) {
                Thread.sleep(200);
            }
        }
        assertThat(opened).as("B 的差额恢复应成功（把 A 那笔还没建行的 5 张补建一行）").isTrue();
        opener.join(30_000);

        // 读数：Σ桶行quota 与模板 allocated（本用例的核心不变量）
        long sumQuota = readLong("SELECT COALESCE(SUM(quota),0) FROM stock_bucket WHERE template_id = ?", tpl);
        long allocated = readLong("SELECT allocated FROM coupon_template WHERE id = ?", tpl);

        // 业务后果：多出来的那行额度能不能真实卖出——修复前还能再卖 10 张（总发放 20 > total 15），修复后只能卖 5 张
        int sold = 0;
        for (int i = 0; i < 10; i++) {
            if ("OK".equals(receiveQuietly(USER.incrementAndGet(), "resume-sale-" + i, tpl))) {
                sold++;
            }
        }
        final int extraSold = sold;   // lambda 捕获需 effectively-final

        assertAll("L37 锁过期后恢复写入的后果",
                () -> assertThat(sumQuota)
                        .as("Σquota ≡ allocated（重复建行 = 多出的一行让 Σquota 超出 allocated）")
                        .isEqualTo(allocated),
                () -> assertThat(extraSold)
                        .as("重复建行的额度真实可卖：修复前 10（总发放 20 > total 15），修复后 5")
                        .isEqualTo(5));
    }

    // ---------- L38 批 1（2026-09-27 评审批 1：开桶恢复/孤儿封账/清牌/终态判据）----------

    @Test
    @Timeout(120)
    @DisplayName("L38·开桶脚本护栏：名册上已是更新世代时，旧世代注册被拒绝（-1），act 不回退")
    void staleGenerationRegistrationRejected() {
        long tpl = createTemplate(15);
        assertThat(stockGateway.openBucket(tpl, 0, 2, 5)).isEqualTo(1);   // 先开 gen2
        assertThat(stockGateway.openBucket(tpl, 0, 1, 5))
                .as("名册上已是 gen2——gen1 的注册必须被拒绝（被暂停后恢复执行的旧世代写入）")
                .isEqualTo(-1);
        assertThat(stockGateway.actEntries(tpl)).as("act 绝不回退到旧世代").containsEntry("0", "2");
    }

    @Test
    @Timeout(120)
    @DisplayName("L38·批量重开不再把未注册行当空代封账：先补注册（孤儿重放），行保持活跃")
    void refillReplaysUnregisteredRowInsteadOfSealing() {
        long tpl = createTemplate(15);   // 2 槽，allocated=10
        drainQuietly(tpl, 10);           // 两桶卖空
        // 手工造"行已提交、注册未执行"的孤儿行（insert 后、openBucket 前被中断的残影）
        update("INSERT INTO stock_bucket (template_id, bucket_no, generation, allocated_hi, quota, used, status)"
                + " VALUES (?, 0, 2, 15, 5, 0, 0)", tpl);

        assertThat(stockBucketRouter.refillExhaustedBuckets(tpl)).isTrue();

        assertThat(readLong("SELECT status FROM stock_bucket WHERE template_id = ? AND bucket_no = 0"
                + " AND generation = 2", tpl))
                .as("L38：未注册的行不得被封账（旧行为=1=额度永久搁浅）").isEqualTo(0L);
        assertThat(stockGateway.actEntries(tpl)).as("孤儿行被补注册进名册").containsEntry("0", "2");
    }

    @Test
    @Timeout(120)
    @DisplayName("L38·清牌协议：新桶建立（带可卖库存）后，停流牌被清掉")
    void soldoutSignClearedWhenNewBucketOpens() {
        long tpl = createTemplate(15);
        drainQuietly(tpl, 10);
        stockGateway.setSoldoutWithTtl(tpl, 10);           // 模拟误牌/残留牌
        assertThat(stockGateway.isSoldout(tpl)).isTrue();

        assertThat(stockBucketRouter.refillExhaustedBuckets(tpl)).isTrue();

        assertThat(stockGateway.isSoldout(tpl))
                .as("L38：新桶带库存——停流牌必须清（B4 清除协议）").isFalse();
    }

    @Test
    @Timeout(120)
    @DisplayName("L38·真售罄三条件：有人持有开桶锁时，请求拿到可重试的 5001 而不是终态 3001")
    void requestWhileOpenerHoldsLock_getsRetryableNotSoldOut() throws Exception {
        long tpl = createTemplate(15);   // 2 槽 × 5 → allocated=10、total=15
        drainQuietly(tpl, 10);           // 两桶排空

        AtomicBoolean slept = new AtomicBoolean(false);
        CountDownLatch atSeam = new CountDownLatch(1);
        FaultHooks.arm(FaultHooks.OPEN_BUCKET_SEAM, args -> {
            if (slept.compareAndSet(false, true)) {
                atSeam.countDown();
                try {
                    Thread.sleep(2_500);   // 持锁停留：其 CAS 已让 allocated==total
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Thread opener = new Thread(() -> stockBucketRouter.refillExhaustedBuckets(tpl), "lock-holder");
        opener.start();
        assertThat(atSeam.await(10, TimeUnit.SECONDS)).as("开桶者已持锁进入缝中").isTrue();

        String r = receiveQuietly(USER.incrementAndGet(), "l38-retryable", tpl);
        assertThat(r).as("L38：有人在开桶时终态售罄是谎报（不可重试=丢单）——必须是可重试的 5001")
                .isEqualTo("5001");

        opener.join(30_000);
        assertThat(receiveQuietly(USER.incrementAndGet(), "l38-after", tpl))
                .as("开桶者完成后，货可正常售出").isEqualTo("OK");
    }

    // ---------- L38 批 2（2026-09-28 评审批 2：REBUILD 未注册世代 / refill 差额护栏）----------

    @Test
    @Timeout(120)
    @DisplayName("批 2·REBUILD 护栏：未注册世代禁止按退役推导（-3 拒绝，不写 void）")
    void rebuildRefusesUnregisteredGeneration() {
        long tpl = createTemplate(15);   // 槽0 gen1 已注册并有余量
        // 造一个"行已提交、注册未执行"的世代：slot0 gen2、act 未登记
        update("INSERT INTO stock_bucket (template_id, bucket_no, generation, allocated_hi, quota, used, status)"
                + " VALUES (?, 0, 2, 15, 5, 0, 0)", tpl);

        var res = stockGateway.rebuildBucket(tpl, 0, 2, "force", 5, 0, false, 300, 0, 0);

        assertThat(res.ret()).as("未注册世代必须拒绝 retired 推导（旧行为=1：新鲜额度被写成 void=不可卖）")
                .isEqualTo(-3);
        assertThat(Boolean.TRUE.equals(redis.hasKey("c:stk:{" + tpl + "}:0:2")))
                .as("不得写任何键").isFalse();
    }

    @Test
    @Timeout(120)
    @DisplayName("批 2·refill 差额护栏：已划出未建行时不再划款建行（防段上界错位=超发，L37 残洞）")
    void refillDefersWhenAllocationGapExists() {
        long tpl = createTemplate(25);   // 桶额 5×2 槽（本类 props）→ 发布开 2 桶(10)，余 15
        drainQuietly(tpl, 10);           // 两桶排空
        // 造差额：allocated 虚增 5（模拟被暂停开桶者已划款、行未建；total=25 仍有余量）
        update("UPDATE coupon_template SET allocated = allocated + 5 WHERE id = ?", tpl);
        long quotaBefore = readLong("SELECT COALESCE(SUM(quota),0) FROM stock_bucket WHERE template_id = ?", tpl);

        assertThat(stockBucketRouter.refillExhaustedBuckets(tpl))
                .as("有差额时必须交给单桶路径的差额恢复（旧行为=true+建行：段上界错位、唯一键撞不上）")
                .isFalse();
        assertThat(readLong("SELECT COALESCE(SUM(quota),0) FROM stock_bucket WHERE template_id = ?", tpl))
                .as("refill 不得建行").isEqualTo(quotaBefore);

        // 单桶路径接手：差额恢复补建一行（不重复 CAS），段上界与差额对齐
        assertThat(stockBucketRouter.tryOpenBucket(tpl)).isTrue();
        assertThat(readLong("SELECT COALESCE(SUM(quota),0) FROM stock_bucket WHERE template_id = ?", tpl))
                .as("差额被补建（Σquota 追平 allocated）").isEqualTo(quotaBefore + 5);
    }

    // ---------- 批 3（2026-09-28 评审批 3：封账不看在途令牌）----------

    @Test
    @Timeout(120)
    @DisplayName("批 3·P2-3 封账看在途：余量 0 但有令牌在飞 → 本轮不封账（封了=在途提交撞 status 守卫被拒）")
    void refillDefersSealWhileTokenInFlight() {
        long tpl = createTemplate(15);   // 桶额 5×2 槽 → 发布开 2 桶(10)，余 5（无人领券，不会再自动开桶）
        // 直接把两桶摆成"卖空"：stk=0、dbi=quota、行 used=quota——跳过领券路径
        // （领券耗尽会触发 -10 自动补开，状态不可控，测不出想测的那条门）
        for (int b = 0; b < 2; b++) {
            redis.opsForValue().set("c:stk:{" + tpl + "}:" + b + ":1", "0");
            redis.opsForValue().set("c:dbi:{" + tpl + "}:" + b + ":1", "5");
        }
        update("UPDATE stock_bucket SET used = quota WHERE template_id = ?", tpl);
        // 槽0 gen1 埋一张在途令牌（DEDUCT 已扣、DB 未提交——这正是封账会打成自伤的那批提交）
        redis.opsForZSet().add("c:pend:{" + tpl + "}:0:1", "inflight-tok", System.currentTimeMillis());

        assertThat(stockBucketRouter.refillExhaustedBuckets(tpl)).isTrue();

        assertAll(
                () -> assertThat(bucketStatus(tpl, 0, 1))
                        .as("在途未清 → 不得封账（旧行为=封：合法领取的提交撞 status 守卫被拒）")
                        .isZero(),
                () -> assertThat(bucketStatus(tpl, 1, 1))
                        .as("另一槽无在途 → 正常封账（门是有区分度的，不是整体停封）")
                        .isEqualTo(1));
    }

    // ---------- L39（2026-09-30）：契约边界——合法输入不得让事务失败 ----------

    @Test
    @Timeout(120)
    @DisplayName("L39·64 字符 requestId（DTO 允许的最大值）：领券应成功（旧行为：event_no 超列宽 → 事务失败=错误拒绝）")
    void maxLengthRequestId_doesNotBreakIssue() {
        long tpl = createTemplate(10);
        String reqId = "r".repeat(64);   // DTO @Size(max=64) 允许的最大合法值

        assertThat(receiveQuietly(USER.incrementAndGet(), reqId, tpl))
                .as("合法长度的 requestId 必须能领到券（旧行为：event_no=\"RCV:\"+userId+\":\"+requestId 超 VARCHAR(64) → 事务整体失败）")
                .isEqualTo("OK");
    }

    // ---------- L42①（2026-09-30）：限领计数虚高 → 不得假拒绝 ----------

    @Test
    @Timeout(120)
    @DisplayName("L42·计数虚高：数据库说没领过 → 自愈计数并成功（旧行为：闸门 -2 → 3002 假拒绝，且永不自愈）")
    void leakedUserLimitCounter_healsInsteadOfFalseReject() {
        long tpl = createTemplate(10);   // perUserLimit=1
        long uid = USER.incrementAndGet();
        // 制造"虚高"：累计计数直接抬到上限（模拟在途令牌被清扫后计数没回滚）
        redis.opsForValue().set("c:ul:{" + tpl + "}:" + uid, "1");

        assertThat(receiveQuietly(uid, "leak-" + uid, tpl))
                .as("数据库里一张都没有 → 必须能领到（旧行为：3002 假拒绝）")
                .isEqualTo("OK");
        assertThat(redis.opsForValue().get("c:ul:{" + tpl + "}:" + uid))
                .as("计数被校准为真实张数（1 张）").isEqualTo("1");
    }

    @Test
    @Timeout(120)
    @DisplayName("L42·对照：真的领满了仍然拒绝（自愈不得放宽限领）")
    void trulyLimitedUser_stillRejected() {
        long tpl = createTemplate(10);
        long uid = USER.incrementAndGet();

        assertThat(receiveQuietly(uid, "first-" + uid, tpl)).isEqualTo("OK");
        assertThat(receiveQuietly(uid, "second-" + uid, tpl))
                .as("第二次必须被限领拦住（3002）——复核只在'数据库说没满'时才自愈").isEqualTo("3002");
    }

    // ---------- L40②（2026-09-30）：两个"零点火"接缝的用例 ----------

    @Test
    @Timeout(120)
    @DisplayName("L40②·回执失败（confirm 故障）：用户仍成功、券已落库、令牌留在在途 → 对账把镜像追平（confirm-lost 自愈）")
    void confirmFailure_doesNotBreakIssue_andReconcileHeals() throws Exception {
        long tpl = createTemplate(10);
        long uid = USER.incrementAndGet();
        FaultHooks.arm(FaultHooks.REDIS_CMD_DELAY, args -> {
            if (args != null && args.length > 0 && "confirm".equals(args[0])) {
                throw new RuntimeException("confirm failed (injected)");
            }
        });

        assertThat(receiveQuietly(uid, "cf-" + uid, tpl))
                .as("回执失败不得影响用户结果（数据库已提交，用户结果已定）").isEqualTo("OK");
        FaultHooks.disarmAll();
        assertThat(redis.opsForZSet().size("c:pend:{" + tpl + "}:0:1"))
                .as("症状：confirm 没执行 → 令牌还挂在在途集合里").isEqualTo(1L);

        Thread.sleep(400);   // 越过 inflight-ttl（本类 300ms）：巡检的清扫会把残留令牌清掉
        reconcileService.checkOne(tpl);   // 第一轮：触发清扫（把残留令牌清掉）并确认破缺
        awaitDeep();
        // 第二轮应干净：confirm-lost 已把镜像追平（dbi=已发放、stk=配额−已发放）
        assertThat(reconcileService.checkOne(tpl)).as("修复后巡检干净").isFalse();
        var gc = stockGateway.conservationAll(tpl, 300);
        long dbiSum = gc.buckets().stream().mapToLong(b -> b.dbi()).sum();
        long stkSum = gc.buckets().stream().mapToLong(b -> b.stk()).sum();
        assertThat(dbiSum).as("镜像追平已发放（合计）").isEqualTo(1L);
        assertThat(stkSum).as("余量按已发放推导（合计 = 10-1）").isEqualTo(9L);
        assertThat(redis.opsForZSet().size("c:pend:{" + tpl + "}:0:1")).as("残留令牌被清扫").isEqualTo(0L);
    }

    @Test
    @Timeout(120)
    @DisplayName("L40②·事务挂起（issue 故障）：探针定案未提交 → 取消记账，库存不泄漏，恢复正常后可领")
    void issueSuspend_restoresStock() {
        long tpl = createTemplate(10);
        long uid = USER.incrementAndGet();
        long stkBefore = bucketStk(tpl, 0, 1);
        FaultHooks.arm(FaultHooks.ISSUE_SUSPEND, args -> {
            throw new RuntimeException("issue suspended (injected)");
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        receiveAppService.receive(new ReceiveRequest("susp-" + uid, uid, tpl)))
                .as("事务故障 → 异常上抛（由全局处理器收敛为 5000 级错误）")
                .isInstanceOf(RuntimeException.class);
        FaultHooks.disarmAll();

        assertThat(bucketStk(tpl, 0, 1)).as("库存必须回加：回滚后不留悬挂扣减").isEqualTo(stkBefore);
        assertThat(redis.opsForZSet().size("c:pend:{" + tpl + "}:0:1"))
                .as("令牌必须出清（取消记账成功）").isEqualTo(0L);
        assertThat(receiveQuietly(uid, "after-" + uid, tpl)).as("恢复正常后应能领到").isEqualTo("OK");
    }

    /** 等 deep（独立线程）跑完（最多 30 秒） */
    private void awaitDeep() throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!reconcileService.deepQueueIdle() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
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

    /** 分代行的封账状态（0=活跃 1=封账） */
    private Integer bucketStatus(long tpl, int bucket, long gen) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT status FROM stock_bucket WHERE template_id = ? AND bucket_no = ? AND generation = ?",
                Integer.class, tpl, bucket, gen);
    }

    private void update(String sql, long tpl) {
        new JdbcTemplate(dataSource).update(sql, tpl);
    }
}
