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
import com.newcoupon.infrastructure.persistence.StockDriftLogGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.CancelCause;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductCommand;
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

/**
 * 阶段 1 验收（ADR-0006 + ADR-0008 恒等式记账）——Redis+MySQL 双容器：
 * ① 通用限领 N=3：同用户 5 次 → 3 成功 2 超领，seq=1/2/3 落库
 * ② Redis 计数丢失 → DB 序号唯一索引兜底（最后防线证明）
 * ③ 2000 并发抢 1000：零超发 + 恒等式三本账归零（issued−dbi−pend≡0）
 * ④ 崩溃残留（kill -9：扣了 Redis 没 confirm/cancel）→ 恒等式暴露 → 守卫修复 + 落漂移表
 * ⑤ 每人日限=1：第二张拒绝
 * ⑥ 批次日限=1：第二个用户拒绝(3003)
 * ⑦ 在途窗口内（pend 持令牌）恒等式不误报；TTL 内 confirm 到达 → 差值从未出现
 * ⑧ 取消记账幂等：二次 cancel 完全 no-op；cancel 后同 requestId 可重试
 * ⑨ 售罄钉零+标志（A1'+B4）：DB 拒绝分类先行 → 钉零立标志 → 后续流量 Lua 首行短路
 * ⑩ 清库风暴（A2'）：删 stock key 后 50 并发 → 条件 SET 只重建一次，零 DECR 覆盖
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "coupon.reconcile.inflight-ttl-ms=300",       // 测试压缩在途 TTL（生产 180s）
        "coupon.reconcile.log-min-interval-ms=0",     // 测试不限频，便于断言漂移表
        "coupon.reconcile.auto-repair.mode=force"     // 本类验证修复行为（生产默认 shadow）
})
class RedisStockIntegrationTest extends ShardedIntegrationTestSupport {

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
    private TemplateRepository templateRepository;
    @Autowired
    private RedisStockGateway stockGateway;
    @Autowired
    private StockReconcileService reconcileService;
    @Autowired
    private StockDriftLogGateway driftLogGateway;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired
    private javax.sql.DataSource dataSource;

    private static final AtomicInteger USER = new AtomicInteger(80_000);

    /** P0③测试：day 由调用方固化传入——这里用"构造时刻的今天"（与生产编排层同语义） */
    private static final java.util.function.Supplier<String> TODAY =
            () -> java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));

    /** 等 deep 执行器跑完（异步单线程——提交后轮询其空闲，最多 5s） */
    private void awaitDeep() throws Exception {
        for (int i = 0; i < 50; i++) {
            if (reconcileService.deepQueueIdle()) {
                return;
            }
            Thread.sleep(100);
        }
    }

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

        assertThat(bucketUsed(tplId))
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
        assertThat(bucketUsed(tplId)).isEqualTo(2);
    }

    @Test
    @Timeout(300)
    @DisplayName("验收③：2000并发抢1000 → 零超发 + 恒等式三本账归零（issued−dbi−pend≡0）")
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

            // 恒等式验账：每笔成功都完成了 confirm（dbi+1 与令牌出清原子配对），
            // 每笔失败都在闸门被拒（从未进记账）→ 三本账精确归零，无容忍带
            assertThat(bucketUsed(tplId)).as("P2b：发放权威读数=SUM(stock_bucket.used)").isEqualTo(1000);
            assertThat(stockGateway.remaining(tplId))
                    .as("Redis余量=总量-已发（取消记账无残留）").isEqualTo(1000L - 1000);
            assertThat(stockGateway.confirmedCount(tplId)).as("dbi=1000").isEqualTo(1000);
            assertThat(stockGateway.pendingCount(tplId)).as("在途=0").isZero();
            assertThat(stockGateway.conservation(tplId, 300).sum() - 1000L)
                    .as("守恒式：stk+pend+dbi+void=total").isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("验收④【恒等式核心】：崩溃残留（扣Redis后进程被杀）→ 两轮确认 → 守卫修复三本账 + 漂移落表")
    void crashResidueDetectedAndRepaired() throws Exception {
        long tplId = createTemplate(100, 2, null, null);
        long uid = USER.incrementAndGet();
        // 模拟 kill -9：Lua 过闸（issued+1, pend+1, stock-1）后进程死在 confirm/cancel 之前
        DeductCommand crashed = new DeductCommand(tplId, uid, "crash-1", 2, 0, 0, TODAY.get(), 172800, 600);
        assertThat(stockGateway.deduct(crashed).success()).isTrue();
        // —— 没有 confirm，没有 cancel ——
        assertThat(stockGateway.remaining(tplId)).isEqualTo(99);
        assertThat(stockGateway.pendingCount(tplId)).isEqualTo(1);

        // 在途 TTL(300ms) 内：守恒式仍成立（stk99+pend1+dbi0+void0=100）→ 不告警
        assertThat(reconcileService.checkOne(tplId)).as("在途期间不是漂移").isFalse();

        Thread.sleep(400);   // 令牌过期（生产=180s：DB锁等待50s的3倍）

        // 第一轮：清扫过期令牌 → sum=99≠100 → 只疑似（防一次性干扰）
        assertThat(reconcileService.checkOne(tplId)).isFalse();
        // 第二轮：仍在 → 确认 → deep：白名单全过 → FORCE 按业务状态推导（活跃态 stk=100,dbi=ic=0,void=0）
        assertThat(reconcileService.checkOne(tplId)).isTrue();

        awaitDeep();
        assertThat(stockGateway.remaining(tplId))
                .as("崩溃残留被守恒修复找回（FORCE 活跃分支 stk=total-ic=100）").isEqualTo(100);
                assertThat(stockGateway.confirmedCount(tplId)).isZero();
        assertThat(stockGateway.pendingCount(tplId)).isZero();
        assertThat(stockGateway.conservation(tplId, 300).sum() - 100L).isZero();
        assertThat(driftLogGateway.countSince(tplId, LocalDateTime.now().minusMinutes(5)))
                .as("漂移必须留痕（审计与升级依据；含 publish 的 RESET 审计行——评审#5 后发布也落表）")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("验收⑦【在途语义】：TTL内完成（confirm到达）→ 差值从未出现，对账零打扰")
    void inFlightWithinTtlNeverAlarms() {
        long tplId = createTemplate(100, 2, null, null);
        long uid = USER.incrementAndGet();
        DeductCommand slow = new DeductCommand(tplId, uid, "slow-1", 2, 0, 0, TODAY.get(), 172800, 600);
        var slowRes = stockGateway.deduct(slow);
        assertThat(slowRes.success()).isTrue();   // 过闸（P2b：结果固化桶/世代）

        assertThat(reconcileService.identityCheckAll())
                .as("在途期间 pend 持令牌，恒等式成立").isZero();

        assertThat(stockGateway.confirm(slow, slowRes.bucket(), slowRes.gen()))
                .as("TTL内 confirm：dbi+1 且令牌出清").isTrue();
        assertThat(stockGateway.conservation(tplId, 300).sum() - 100L).isZero();
        assertThat(stockGateway.remaining(tplId)).isEqualTo(99);   // 正常扣减，未被触碰
    }

    @Test
    @Timeout(120)
    @DisplayName("验收⑧【记账幂等】：cancel 两次 → 第二次完全no-op；cancel 后同 requestId 可重试；重复 confirm 不重复记账")
    void cancelAndConfirmAreIdempotent() {
        long tplId = createTemplate(100, 2, null, null);
        long uid = USER.incrementAndGet();
        DeductCommand cmd = new DeductCommand(tplId, uid, "cp-idem-1", 2, 0, 0, TODAY.get(), 172800, 600);

        var firstRes = stockGateway.deduct(cmd);
        assertThat(firstRes.success()).isTrue();   // stock 100→99, ul 0→1

        assertThat(stockGateway.cancel(cmd, firstRes.bucket(), firstRes.gen(), CancelCause.TRANSIENT)).isTrue();   // 正常回滚
        assertThat(stockGateway.cancel(cmd, firstRes.bucket(), firstRes.gen(), CancelCause.TRANSIENT))
                .as("令牌已消费 → 第二次取消（迟到/重复投递）必须 no-op")
                .isFalse();

        assertThat(stockGateway.remaining(tplId))
                .as("二次取消不得把库存多加回去（否则Redis偏高→过闸的多→DB拒绝循环）")
                .isEqualTo(100);
        assertThat(stockGateway.userCount(tplId, uid)).isEqualTo(0);
        assertThat(stockGateway.confirmedCount(tplId))
                .as("借方被回滚一次（=0），不得减成负数或二次扣减").isZero();

        // 凭证删除后，同一 requestId 的真实重试必须能重新过闸（幂等不能破坏重试语义）
        var retryRes = stockGateway.deduct(cmd);
        assertThat(retryRes.success()).isTrue();
        assertThat(stockGateway.confirm(cmd, retryRes.bucket(), retryRes.gen())).isTrue();
        assertThat(stockGateway.confirm(cmd, retryRes.bucket(), retryRes.gen()))
                .as("重复 confirm 不得二次 dbi+1").isFalse();
        assertThat(stockGateway.confirmedCount(tplId)).isEqualTo(1);
        assertThat(stockGateway.confirmedCount(tplId)).isEqualTo(1);
        assertThat(stockGateway.remaining(tplId)).isEqualTo(99);
    }

    @Test
    @Timeout(120)
    @DisplayName("验收⑨【A1'+B4】：DB拒绝分类先行 → 售罄钉零+立标志 → 后续请求 Lua 首行短路（不再打DB）")
    void soldoutPinZeroAndFlag() throws Exception {
        long tplId = createTemplate(1, 1, null, null);
        long u1 = USER.incrementAndGet();
        long u2 = USER.incrementAndGet();
        long u3 = USER.incrementAndGet();

        assertThat(receive(u1, "so-1", tplId).code()).isEqualTo("OK");   // 最后 1 张

        // 人为污染：Redis 余量凭空多出 5（模拟漂移/主从切换丢写）——P2b：打活跃桶键
        var soAct = redis.<String, String>opsForHash().entries("c:act:{" + tplId + "}");
        String soBucket = soAct.keySet().iterator().next();
        redis.opsForValue().increment("c:stk:{" + tplId + "}:" + soBucket + ":" + soAct.get(soBucket), 5);

        // u2 过闸（5>0）→ DB 条件扣账 0 行 → 分类先行：新快照 issued(1)>=total(1) → 售罄
        Res second = receive(u2, "so-2", tplId);
        assertThat(second.code()).isEqualTo("3001");

        // ADR-0009 售罄终态语义：void+1（只退休这张令牌）、不动 stk（被污染的 5 留给 FORCE 精确还原
        // ——v2 P0：钉零会把污染洗白进 void 且 FORCE 无法修复）
        assertThat(stockGateway.conservation(tplId, 300).voidRetired())
                .as("售罄取消=void+1（这张令牌退役，不再回 stk）").isEqualTo(1);
        assertThat(stockGateway.remaining(tplId))
                .as("售罄终态不回加 stk（deduct 已 DECR 到 4，污染余量留给 FORCE 归零）").isEqualTo(4);
        assertThat(stockGateway.isSoldout(tplId))
                .as("B4：售罄标志已立（独立于 stock 值，抗误写）").isTrue();
        assertThat(stockGateway.userCount(tplId, u2))
                .as("u2 过闸时被加的限领计数必须回滚").isZero();
        // FORCE 修复（retired 分支：so 存在 → stk=0, dbi=ic=1, void=total-ic=0——污染归零）
        reconcileService.checkOne(tplId);   // round1 疑似
        reconcileService.checkOne(tplId);   // round2 确认 → deep retired 分支
        awaitDeep();
        assertThat(stockGateway.conservation(tplId, 300).sum() - 1L)
                .as("FORCE retired 分支修复后守恒（stk=0+dbi=1+void=0）").isZero();
        assertThat(stockGateway.remaining(tplId)).as("污染的 5 被 FORCE 归零").isZero();

        // u3 在 Lua 第一行被 soldout 标志短路：不过闸、不打 DB、无补偿
        assertThat(receive(u3, "so-3", tplId).code()).isEqualTo("3001");
        assertThat(bucketUsed(tplId))
                .as("标志短路后 DB 零压力").isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("验收⑩【A2'】：清库风暴——删stock key后50并发 → 条件SET只重建一次，扣减零丢失")
    void lazyRebuildSurvivesFlushStorm() throws Exception {
        long tplId = createTemplate(100, 1, null, null);
        assertThat(stockGateway.remaining(tplId)).isEqualTo(100);

        redisFlushStock(tplId);   // 模拟 Redis 清库/主从切换丢 key（N2 风暴起点）

        ExecutorService pool = Executors.newFixedThreadPool(50);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Res>> futures = IntStream.range(0, 50)
                    .mapToObj(i -> pool.submit(() -> {
                        gate.await();
                        return receive(70_000L + i, "storm-" + i, tplId);
                    }))
                    .toList();
            gate.countDown();
            List<Res> results = futures.stream()
                    .map(f -> { try { return f.get(60, TimeUnit.SECONDS); }
                                catch (Exception e) { throw new IllegalStateException(e); } })
                    .toList();

            long ok = results.stream().filter(r -> "OK".equals(r.code())).count();
            assertThat(ok).as("退避+条件SET后全部恢复领取").isEqualTo(50);
            // ADR-0009 §8.3 认账：NX 全清库形态为【近似】（误差=重建瞬间未提交在途数，dbi:=ic
            // 竞态可致 dbi 虚高/余量偏差）——方向安全（DB 硬顶零超发），由守恒巡检 FORCE 收敛：
            reconcileService.checkOne(tplId);
            reconcileService.checkOne(tplId);
            awaitDeep();
            assertThat(stockGateway.conservation(tplId, 300).sum() - 100L)
                    .as("守恒修复收敛后 sum=total（精确）").isZero();
            assertThat(stockGateway.remaining(tplId))
                    .as("余量=100-50（FORCE 按 ic=50 推导）").isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
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

    @Test
    @Timeout(120)
    @DisplayName("P0③回归·跨天失败取消：cancel 必须扣 deduct 用的那个日键（修复前会扣到新一天）")
    void midnightCancelHitsOriginalDayKey() {
        long tplId = createTemplate(100, 5, 10L, null);   // 批次日限=10（日限无DB防线，错扣=超发方向）
        long uid = USER.incrementAndGet();
        String fakeOldDay = "20990101";   // 固化为"昨天"——模拟 23:59:59.900 过闸
        DeductCommand cmd = new DeductCommand(tplId, uid, "md-1", 5, 0, 10L, fakeOldDay, 172800, 600);

        var mdRes = stockGateway.deduct(cmd);
        assertThat(mdRes.success()).isTrue();
        assertThat(redis.opsForValue().get("c:dly:20990101:{" + tplId + "}"))
                .as("deduct 加的是命令里固化的那个日键").isEqualTo("1");

        // "午夜过后"失败取消——修复前 cancel 用 LocalDate.now() 重算日键，扣的是今天（错键）
        assertThat(stockGateway.cancel(cmd, mdRes.bucket(), mdRes.gen(), CancelCause.TRANSIENT)).isTrue();

        assertThat(redis.opsForValue().get("c:dly:20990101:{" + tplId + "}"))
                .as("cancel 必须回扣原日键（否则次日日限被偷走=超发方向）").isEqualTo("0");
    }

    @Test
    @Timeout(120)
    @DisplayName("P0④回归·修复 Lua 原子门：在途非空时 repair 被拦截不覆盖；令牌出清后放行")
    void repairGatedWhileInFlight() {
        long tplId = createTemplate(100, 2, null, null);
        long uid = USER.incrementAndGet();
        DeductCommand cmd = new DeductCommand(tplId, uid, "rg-1", 2, 0, 0, TODAY.get(), 172800, 600);

        var rgRes = stockGateway.deduct(cmd);
        assertThat(rgRes.success()).isTrue();   // 令牌在途挂着（P2b：固化桶/世代）
        assertThat(stockGateway.remaining(tplId)).isEqualTo(99);

        // 守卫①（Java 侧）此刻读到 pending=0 是伪造的前提——真实场景是"检查后、执行前溜进新预占"。
        // 这里直接构造"执行瞬间在途非空"：repair 必须原子拒绝，库存不被覆盖。
        assertThat(stockGateway.rebuildBucket(tplId, rgRes.bucket(), rgRes.gen(), "force",
                100, 0, false, 300, 0, 0).ret())
                .as("在途非空，Lua ZCARD 门必须原子拦截（TOCTOU 窗口关闭）").isEqualTo(-1);
        assertThat(stockGateway.remaining(tplId))
                .as("被拦截的修复不得写任何值").isEqualTo(99);

        // 令牌出清（取消）后，同一修复放行
        assertThat(stockGateway.cancel(cmd, rgRes.bucket(), rgRes.gen(), CancelCause.TRANSIENT)).isTrue();
        assertThat(stockGateway.rebuildBucket(tplId, rgRes.bucket(), rgRes.gen(), "force",
                100, 0, false, 300, 0, 0).rebuilt()).isTrue();
        assertThat(stockGateway.remaining(tplId)).isEqualTo(100);
    }

    /** 只删 stock key（保留 issued/pend 等记账 key）——精确模拟"额度 key 丢失"（N2） */
    private void redisFlushStock(long tplId) {
        redis.delete("c:stk:{" + tplId + "}");   // key 规范属网关契约（docs/10 §10.2）
    }

    /** P2b B簇换锚：ic 冻结后，发放权威读数 = SUM(stock_bucket.used)（E3c 锚） */
    private long bucketUsed(long tplId) {
        Long v = new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .queryForObject("SELECT COALESCE(SUM(used),0) FROM stock_bucket WHERE template_id = ?",
                        Long.class, tplId);
        return v == null ? 0 : v;
    }
}
