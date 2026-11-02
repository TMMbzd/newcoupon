package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.StockAdjustRequest;
import com.newcoupon.application.dto.StockAdjustResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.C1StockReconcileService;
import com.newcoupon.application.service.StockShardAdjustService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TemplateLifecycleService;
import com.newcoupon.common.BizException;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockAdjustLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
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
 * C1 库存模型行为契约（ADR-0018）：新模板走 DB 固定分片，Redis 不存库存。
 *
 * <p>本类只开 C1 开关；旧链路测试类默认仍走 legacy。共享 MySQL/Redis 容器时，
 * 每个用例使用独立模板和独立 userId，避免测试间数据串扰。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.stock.shard-count=8",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class C1StockIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger USER = new AtomicInteger(300_000);

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private CouponStockShardMapper shardMapper;
    @Autowired
    private CouponStockAdjustLogMapper adjustLogMapper;
    @Autowired
    private StockShardAdjustService adjustService;
    @Autowired
    private TemplateLifecycleService lifecycleService;
    @Autowired
    private C1StockReconcileService c1ReconcileService;
    @Autowired
    private TemplateMapper templateMapper;
    @Autowired
    private UserCouponRepository userCouponRepository;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private CouponEventRepository couponEventRepository;
    @Autowired
    private javax.sql.DataSource dataSource;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    private long createC1Template(long total, int perUserLimit) {
        return createC1Template(total, perUserLimit, 0L, 0);
    }

    private long createC1Template(long total, int perUserLimit,
                                  long dailyLimit, int perUserDailyLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "C1库存验证券", 1001L, 1, 1000L, null, 10000L, total, perUserLimit,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                dailyLimit, perUserDailyLimit));
        templateAppService.publish(created.id());
        return created.id();
    }

    private record Outcome(String code, Long couponId, boolean duplicated) {
    }

    private Outcome receive(long userId, String requestId, long templateId) {
        try {
            ReceiveResult r = receiveAppService.receive(new ReceiveRequest(requestId, userId, templateId));
            return new Outcome("OK", r.couponId(), r.duplicated());
        } catch (BizException e) {
            return new Outcome(String.valueOf(e.getErrorCode().getCode()), null, false);
        }
    }

    @Test
    @DisplayName("发布建布局：N_eff 行，Σquota=total，Σused=0")
    void publishCreatesLayout() {
        long templateId = createC1Template(53, 1);

        List<CouponStockShardPO> rows = shardMapper.selectByTemplate(templateId);
        assertThat(rows).hasSize(8);   // min(8,53)
        assertThat(rows.stream().mapToLong(CouponStockShardPO::getQuota).sum()).isEqualTo(53);
        assertThat(rows.stream().mapToLong(CouponStockShardPO::getUsed).sum()).isZero();
        assertThat(rows.stream().allMatch(r -> r.getQuota() >= r.getUsed())).isTrue();
        assertThat(c1ReconcileService.auditAll()).as("新建布局应通过 C1 最小对账").isZero();
    }

    @Test
    @DisplayName("领取：分片 used+1，券/事件/发件箱同事务落库；重复 requestId 只发一张")
    void receiveAndDuplicate() {
        long templateId = createC1Template(10, 2);
        long userId = USER.incrementAndGet();
        String requestId = "c1-idem-" + userId;

        Outcome first = receive(userId, requestId, templateId);
        assertThat(first.code()).isEqualTo("OK");
        assertThat(first.duplicated()).isFalse();
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);

        UserCoupon coupon = userCouponRepository.findByIdempotentKey(userId, "1", requestId).orElseThrow();
        assertThat(coupon.getTemplateId()).isEqualTo(templateId);
        assertThat(coupon.getSeq()).isEqualTo(1);

        Outcome replay = receive(userId, requestId, templateId);
        assertThat(replay.code()).isEqualTo("OK");
        assertThat(replay.duplicated()).isTrue();
        assertThat(replay.couponId()).isEqualTo(first.couponId());
        assertThat(shardMapper.sumUsed(templateId)).as("重放不能重复扣库存").isEqualTo(1);
        assertThat(couponEventRepository.countIssuedByTemplateSince(
                templateId, LocalDate.now().atStartOfDay())).isEqualTo(1);
        assertThat(outboxCount(templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("每人限领：同用户不同 requestId 只成功 limit 张，失败不扣库存")
    void perUserLimit() {
        long templateId = createC1Template(100, 1);
        long userId = USER.incrementAndGet();

        assertThat(receive(userId, "c1-limit-1", templateId).code()).isEqualTo("OK");
        assertThat(receive(userId, "c1-limit-2", templateId).code()).isEqualTo("3002");
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
        assertThat(userCouponRepository.countByUserAndTemplate(userId, templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("每人日限：同步路径走 V27 计数，第二张 3002，库存/券都不增")
    void perUserDailyLimitUsesCounter() {
        long templateId = createC1Template(10, 5, 0L, 1);
        long userId = USER.incrementAndGet();

        assertThat(receive(userId, "c1-ud-1", templateId).code()).isEqualTo("OK");
        assertThat(receive(userId, "c1-ud-2", templateId).code()).isEqualTo("3002");
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
        assertThat(userCouponRepository.countByUserAndTemplate(userId, templateId)).isEqualTo(1);
        assertThat(userDailyUsed(userId, templateId)).as("每人日计数应停在 1").isEqualTo(1);
    }

    @Test
    @DisplayName("模板日限：同步路径走 V27 计数，第二个用户 3003，库存/券都不增")
    void templateDailyLimitUsesCounter() {
        long templateId = createC1Template(10, 1, 1L, 0);

        assertThat(receive(USER.incrementAndGet(), "c1-td-1", templateId).code()).isEqualTo("OK");
        assertThat(receive(USER.incrementAndGet(), "c1-td-2", templateId).code()).isEqualTo("3003");
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
        assertThat(userCouponRepository.countByTemplate(templateId)).isEqualTo(1);
        assertThat(templateDailyUsed(templateId)).as("模板日计数应停在 1").isEqualTo(1);
    }

    @Test
    @DisplayName("库存售罄回滚模板日计数：第二个用户失败后计数仍为 1")
    void stockFailureRollsBackTemplateDailyCounter() {
        long templateId = createC1Template(1, 1, 10L, 0);

        assertThat(receive(USER.incrementAndGet(), "c1-rb-1", templateId).code()).isEqualTo("OK");
        assertThat(receive(USER.incrementAndGet(), "c1-rb-2", templateId).code()).isEqualTo("3001");
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
        assertThat(templateDailyUsed(templateId))
                .as("失败事务必须把模板日计数回滚到 1").isEqualTo(1);
    }

    @Test
    @DisplayName("库存售罄回滚每人日计数：补库存后同一用户还能领第二张")
    void stockFailureRollsBackUserDailyCounter() {
        long templateId = createC1Template(1, 2, 0L, 2);
        long userId = USER.incrementAndGet();

        assertThat(receive(userId, "c1-urb-1", templateId).code()).isEqualTo("OK");
        assertThat(receive(userId, "c1-urb-2", templateId).code()).isEqualTo("3001");
        assertThat(userDailyUsed(userId, templateId))
                .as("售罄失败不能消耗每人日限").isEqualTo(1);

        adjustService.adjust(templateId,
                new StockAdjustRequest("c1-urb-up", 1L, 2L, "回滚测试补库存", "tester"));
        assertThat(receive(userId, "c1-urb-3", templateId).code()).isEqualTo("OK");
        assertThat(userDailyUsed(userId, templateId)).isEqualTo(2);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(2);
    }

    @Test
    @DisplayName("序号空洞：MAX(seq)=2 但只有 1 张券时，补最小空闲 seq=1")
    void seqHoleIsHealed() {
        long templateId = createC1Template(10, 2);
        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();
        long userId = USER.incrementAndGet();

        // 模拟异步 gate 预分配 seq=2、seq=1 被拒绝后留下的空洞。
        userCouponRepository.save(UserCoupon.issue(
                template, userId, "seed-hole-seq2", 2, LocalDateTime.now()));
        incrementStockUsed(templateId, 0);

        assertThat(receive(userId, "c1-hole-1", templateId).code()).isEqualTo("OK");
        UserCoupon issued = userCouponRepository
                .findByIdempotentKey(userId, template.getAppId(), "c1-hole-1").orElseThrow();
        assertThat(issued.getSeq()).as("应补最小空闲序号 1，而不是返回 3002").isEqualTo(1);
        assertThat(userCouponRepository.countByUserAndTemplate(userId, templateId)).isEqualTo(2);

        assertThat(receive(userId, "c1-hole-2", templateId).code())
                .as("补满两个序号后才应返回 3002").isEqualTo("3002");
    }

    @Test
    @Timeout(120)
    @DisplayName("模板日限并发：40 个不同用户抢 dailyLimit=5，恰好 5 成功且无死锁泄漏")
    void templateDailyLimitConcurrentNoDeadlock() throws Exception {
        long templateId = createC1Template(100, 1, 5L, 0);
        int tasks = 40;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Outcome>> futures = IntStream.range(0, tasks)
                    .mapToObj(i -> pool.submit(() -> {
                        go.await();
                        return receive(USER.incrementAndGet(), "c1-dlc-" + i, templateId);
                    }))
                    .toList();
            go.countDown();
            List<Outcome> results = futures.stream().map(f -> {
                try {
                    return f.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return new Outcome("EX:" + e.getClass().getSimpleName(), null, false);
                }
            }).toList();

            long success = results.stream().filter(o -> "OK".equals(o.code())).count();
            var failures = results.stream().filter(o -> !"OK".equals(o.code()))
                    .collect(java.util.stream.Collectors.groupingBy(Outcome::code,
                            java.util.stream.Collectors.counting()));
            assertThat(success).as("日限 5，失败分布=" + failures).isEqualTo(5);
            assertThat(failures.keySet()).as("只能因达限失败，不能出现 5xxx/EX")
                    .allMatch("3003"::equals);
            assertThat(templateDailyUsed(templateId)).isEqualTo(5);
            assertThat(shardMapper.sumUsed(templateId)).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("并发：200 请求抢 50 库存，成功恰好 50，Σused=50，无重复券")
    void concurrentNoOversell() throws Exception {
        long templateId = createC1Template(50, 1);
        int tasks = 200;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Outcome>> futures = IntStream.range(0, tasks)
                    .mapToObj(i -> pool.submit(() -> {
                        go.await();
                        return receive(USER.incrementAndGet(), "c1-conc-" + i, templateId);
                    }))
                    .toList();
            go.countDown();
            List<Outcome> results = futures.stream().map(f -> {
                try {
                    return f.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return new Outcome("EX:" + e.getClass().getSimpleName(), null, false);
                }
            }).toList();

            long success = results.stream().filter(o -> "OK".equals(o.code())).count();
            var failureDist = results.stream().filter(o -> !"OK".equals(o.code()))
                    .collect(java.util.stream.Collectors.groupingBy(Outcome::code,
                            java.util.stream.Collectors.counting()));
            assertThat(success).as("成功数必须等于库存；失败分布=" + failureDist).isEqualTo(50);
            assertThat(results.stream().filter(o -> "OK".equals(o.code()))
                    .map(Outcome::couponId).distinct().count()).isEqualTo(50);
            assertThat(shardMapper.sumUsed(templateId)).isEqualTo(50);
            assertThat(failureDist.keySet()).allMatch(k -> "3001".equals(k) || k.startsWith("EX:"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("售罄：库存发完后返回 3001，并留下短 TTL 售罄提示")
    void soldOutAndHint() {
        long templateId = createC1Template(2, 1);
        assertThat(receive(USER.incrementAndGet(), "c1-sold-1", templateId).code()).isEqualTo("OK");
        assertThat(receive(USER.incrementAndGet(), "c1-sold-2", templateId).code()).isEqualTo("OK");

        assertThat(receive(USER.incrementAndGet(), "c1-sold-3", templateId).code()).isEqualTo("3001");
        assertThat(redis.hasKey("c:so:{" + templateId + "}")).isTrue();
    }

    @Test
    @DisplayName("Redis 库存键被删：C1 仍可继续领，DB 分片是权威")
    void redisStockKeysDoNotMatter() {
        long templateId = createC1Template(5, 1);
        assertThat(receive(USER.incrementAndGet(), "c1-redis-1", templateId).code()).isEqualTo("OK");

        // C1 不写 c:stk/c:pend 等旧库存键；删掉 c:so 也不影响 DB 分片
        redis.delete("c:so:{" + templateId + "}");
        assertThat(receive(USER.incrementAndGet(), "c1-redis-2", templateId).code()).isEqualTo("OK");
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(2);
    }

    @Test
    @DisplayName("对账发现手工漂移：只告警，不自动改库存")
    void reconcileDetectsInjectedDrift() {
        long templateId = createC1Template(10, 1);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE coupon_stock_shard SET used = used + 1 "
                             + "WHERE template_id = ? AND shard_no = 0")) {
            ps.setLong(1, templateId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        } catch (Exception e) {
            throw new AssertionError("inject stock drift failed", e);
        }
        try {
            assertThat(c1ReconcileService.auditAll())
                    .as("手工改 used 后对账必须发现差异").isGreaterThan(0);
            assertThat(shardMapper.sumUsed(templateId))
                    .as("对账只告警，不能自动把 used 改回去").isEqualTo(1);
        } finally {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE coupon_stock_shard SET used = used - 1 "
                                 + "WHERE template_id = ? AND shard_no = 0")) {
                ps.setLong(1, templateId);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new AssertionError("restore stock drift failed", e);
            }
        }
    }

    @Test
    @DisplayName("旧模板缓存不能突破 DB 新限领：事务内按当前模板复核")
    void staleTemplateCacheCannotExceedDbLimit() {
        long templateId = createC1Template(10, 2);
        long userId = USER.incrementAndGet();
        assertThat(receive(userId, "stale-limit-1", templateId).code()).isEqualTo("OK");

        // 模拟另一个实例改了限领，但本实例 Caffeine 仍缓存旧值（perUserLimit=2）。
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE coupon_template SET per_user_limit = 1 WHERE id = ?")) {
            ps.setLong(1, templateId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        } catch (Exception e) {
            throw new AssertionError("update template limit failed", e);
        }

        assertThat(receive(userId, "stale-limit-2", templateId).code())
                .as("事务内读到 DB 新限领 1，第二张必须 3002").isEqualTo("3002");
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("冻结/解冻/下线/重上线：停发与恢复不改 used")
    void freezeOfflineReonline() {
        long templateId = createC1Template(2, 1);

        lifecycleService.freeze(templateId);
        assertThat(receive(USER.incrementAndGet(), "life-freeze", templateId).code())
                .isEqualTo("5002");
        lifecycleService.unfreeze(templateId);
        assertThat(receive(USER.incrementAndGet(), "life-unfreeze", templateId).code())
                .isEqualTo("OK");

        lifecycleService.offline(templateId);
        assertThat(receive(USER.incrementAndGet(), "life-offline", templateId).code())
                .isEqualTo("2002");
        lifecycleService.reonline(templateId);
        assertThat(receive(USER.incrementAndGet(), "life-reonline", templateId).code())
                .isEqualTo("OK");
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(2);
    }

    @Test
    @DisplayName("调整库存：增量/减量只改 quota，目标低于已发拒绝，requestId 幂等")
    void adjustStockIncreaseDecreaseAndIdempotency() {
        long templateId = createC1Template(10, 1);
        assertThat(receive(USER.incrementAndGet(), "c1-adj-r1", templateId).code()).isEqualTo("OK");
        assertThat(receive(USER.incrementAndGet(), "c1-adj-r2", templateId).code()).isEqualTo("OK");
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(2);

        StockAdjustResult up = adjustService.adjust(templateId,
                new StockAdjustRequest("adj-up-1", 10L, 20L, "补库存", "tester"));
        assertThat(up.totalCount()).isEqualTo(20);
        assertThat(up.usedCount()).isEqualTo(2);
        assertThat(up.idempotent()).isFalse();
        assertThat(templateMapper.selectById(templateId).getTotalCount()).isEqualTo(20);
        assertThat(shardMapper.sumQuota(templateId)).isEqualTo(20);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(2);
        assertThat(adjustLogMapper.selectByRequest(templateId, "adj-up-1")).isNotNull();

        // 幂等重放：不再改额度，返回同一目标总量
        StockAdjustResult replay = adjustService.adjust(templateId,
                new StockAdjustRequest("adj-up-1", 10L, 20L, "补库存", "tester"));
        assertThat(replay.idempotent()).isTrue();
        assertThat(replay.totalCount()).isEqualTo(20);
        assertThat(shardMapper.sumQuota(templateId)).isEqualTo(20);

        StockAdjustResult down = adjustService.adjust(templateId,
                new StockAdjustRequest("adj-down-1", 20L, 5L, "缩库存", "tester"));
        assertThat(down.totalCount()).isEqualTo(5);
        assertThat(shardMapper.sumQuota(templateId)).isEqualTo(5);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(2);

        // 目标低于已发：拒绝，且总量/额度不变
        assertThatThrownBy(() -> adjustService.adjust(templateId,
                new StockAdjustRequest("adj-bad-1", 5L, 1L, "不允许减到已发以下", "tester")))
                .isInstanceOf(BizException.class);
        assertThat(templateMapper.selectById(templateId).getTotalCount()).isEqualTo(5);
        assertThat(shardMapper.sumQuota(templateId)).isEqualTo(5);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(2);
    }

    @Test
    @DisplayName("调整库存后清售罄提示：售罄模板补库存，下一请求可继续领")
    void adjustClearsSoldOutHint() {
        long templateId = createC1Template(1, 1);
        assertThat(receive(USER.incrementAndGet(), "c1-adj-sold-1", templateId).code()).isEqualTo("OK");
        assertThat(receive(USER.incrementAndGet(), "c1-adj-sold-2", templateId).code()).isEqualTo("3001");
        assertThat(redis.hasKey("c:so:{" + templateId + "}")).isTrue();

        adjustService.adjust(templateId,
                new StockAdjustRequest("adj-sold-up", 1L, 3L, "售罄后补库存", "tester"));
        assertThat(redis.hasKey("c:so:{" + templateId + "}")).isFalse();
        assertThat(receive(USER.incrementAndGet(), "c1-adj-sold-3", templateId).code()).isEqualTo("OK");
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(2);
    }

    private long userDailyUsed(long userId, long templateId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT used FROM coupon_user_daily_counter "
                             + "WHERE user_id = ? AND template_id = ? AND stat_day = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, templateId);
            ps.setDate(3, java.sql.Date.valueOf(LocalDate.now()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new AssertionError("read coupon_user_daily_counter failed", e);
        }
    }

    private long templateDailyUsed(long templateId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT used FROM coupon_daily_counter "
                             + "WHERE template_id = ? AND stat_day = ?")) {
            ps.setLong(1, templateId);
            ps.setDate(2, java.sql.Date.valueOf(LocalDate.now()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new AssertionError("read coupon_daily_counter failed", e);
        }
    }

    private void incrementStockUsed(long templateId, int shardNo) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE coupon_stock_shard SET used = used + 1 "
                             + "WHERE template_id = ? AND shard_no = ?")) {
            ps.setLong(1, templateId);
            ps.setInt(2, shardNo);
            if (ps.executeUpdate() != 1) {
                throw new AssertionError("seed stock shard not found: " + shardNo);
            }
        } catch (Exception e) {
            throw new AssertionError("seed coupon_stock_shard failed", e);
        }
    }

    private long outboxCount(long templateId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM coupon_outbox WHERE payload LIKE ?")) {
            ps.setString(1, "%\"templateId\":" + templateId + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new AssertionError("read coupon_outbox failed", e);
        }
    }
}
