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
        "coupon.reconcile.auto-repair.mode=force",   // L44 用例要验证"真写回"（生产默认 shadow=只算不写）
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
    @Autowired
    private com.newcoupon.application.service.StockReconcileService reconcileService;
    @Autowired
    private javax.sql.DataSource dataSource;

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

    @Test
    @Timeout(180)
    @DisplayName("批3·L44/L46·分配镜像偏大（多计一块额度）→ deep 全世代重建 + 覆盖写镜像 → 巡检恢复干净、冻结自动解除")
    void allocMirrorInflation_healedByDeep() throws Exception {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        // total=25、桶额 10×2 槽：发布开 2 桶(20)；排空 21 张 → 批量重开封 2 个真代 + 开尾桶(5)
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "镜像偏大自愈验证券", 1001L, 1, 1000L, null, 10000L, 25L, 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, null, null));
        templateAppService.publish(t.id());
        long tpl = t.id();
        for (int i = 0; i < 21; i++) {
            assertThat(receiveQuietly(USER.incrementAndGet(), "mir-" + tpl + "-" + i, tpl))
                    .as("铺水线领券应成功（第 21 张触发批量重开）").isEqualTo("OK");
        }
        // 清库（Redis 失忆）→ 下一请求触发 ENSURE：只把"活跃代"写进名册，
        // 两个封账真代所在桶在名册里没有字段——这正是 L46 要放行的形态
        List<String> doomed = new ArrayList<>();
        doomed.add("c:act:{" + tpl + "}");
        doomed.add("c:gens:{" + tpl + "}");
        doomed.add("c:alloc:{" + tpl + "}");
        for (var e : redis.<String, String>opsForHash().entries("c:act:{" + tpl + "}").entrySet()) {
            String base = "{" + tpl + "}:" + e.getKey() + ":" + e.getValue();
            doomed.add("c:stk:" + base);
            doomed.add("c:dbi:" + base);
            doomed.add("c:void:" + base);
            doomed.add("c:pend:" + base);
        }
        redis.delete(doomed);
        assertThat(receiveQuietly(USER.incrementAndGet(), "mir-heal", tpl)).as("ENSURE 后应能继续领").isEqualTo("OK");

        long dbQuota = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quota),0) FROM stock_bucket WHERE template_id = ?", Long.class, tpl);
        assertThat(dbQuota).as("镜像口径额度合计 = 10+10+5").isEqualTo(25L);

        // 制造"镜像偏大"：镜像多加 5（模拟重放双计/人工误操作）——E2a 与 E2b 同时破缺、危险方向
        redis.opsForValue().increment("c:alloc:{" + tpl + "}", 5);
        assertThat(reconcileService.checkOne(tpl)).as("破缺应被当场确认（并冻结+提交 deep）").isTrue();

        // 等 deep（独立线程）跑完
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!reconcileService.deepQueueIdle() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(reconcileService.deepQueueIdle()).as("deep 应在超时前跑完").isTrue();

        // 修复后：镜像回到数据库口径（覆盖写），三检全平，冻结被下一轮干净轮自动解除
        assertThat(Long.parseLong(redis.opsForValue().get("c:alloc:{" + tpl + "}")))
                .as("分配镜像应被覆盖写回数据库口径（旧行为：无人能修 → 永久冻结）").isEqualTo(dbQuota);
        assertThat(reconcileService.checkOne(tpl)).as("修复后巡检必须干净").isFalse();
        var gc = stockGateway.conservationAll(tpl, 300);
        assertThat(gc.redisSum()).as("E2a：Σ四键 ≡ c:alloc").isEqualTo(gc.alloc());
        assertThat(stockGateway.isFrozen(tpl)).as("干净轮自动解冻（来源=auto）").isFalse();
    }

    @Test
    @Timeout(180)
    @DisplayName("批 2·ENSURE 全世代口径：含封账真代+历史行(0,0)的模板清库重建后，巡检一轮干净（E2b/镜像不破缺）")
    void ensureRebuildsSealedGensAndLegacyRow_patrolClean() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        // 模板 total=25、桶额 10×2 槽（本类 props）：发布开 2 桶(20)，排空后第 21 张触发批量重开
        // （封 2 个真代 used=10 + 开尾桶 quota=5）——制造"封账真代"形态
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "ENSURE全世代验证券", 1001L, 1, 1000L, null, 10000L, 25L, 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, null, null));
        templateAppService.publish(t.id());
        long tpl = t.id();
        for (int i = 0; i < 21; i++) {
            assertThat(receiveQuietly(USER.incrementAndGet(), "ens2-" + tpl + "-" + i, tpl))
                    .as("铺水线领券应成功（第 21 张触发批量重开）").isEqualTo("OK");
        }
        Integer sealedGens = jdbc.queryForObject("SELECT COUNT(*) FROM stock_bucket WHERE template_id = ?"
                + " AND status = 1 AND NOT (bucket_no = 0 AND generation = 0)", Integer.class, tpl);
        assertThat(sealedGens).as("批量重开应留下 2 个封账真代").isEqualTo(2);

        // 追加 V10 历史行 (0,0)：quota=used=2、封账；allocated 同步 +2（模拟存量模板被回填后的形态）
        jdbc.update("INSERT INTO stock_bucket (template_id, bucket_no, generation, allocated_hi, quota, used, status)"
                + " VALUES (?, 0, 0, 1, 2, 2, 1)", tpl);
        jdbc.update("UPDATE coupon_template SET allocated = allocated + 2 WHERE id = ?", tpl);

        // 灾难：删光该模板的 Redis 键（act/gens/alloc + 各代四键）——Redis 对它彻底失忆
        List<String> doomed = new ArrayList<>();
        doomed.add("c:act:{" + tpl + "}");
        doomed.add("c:gens:{" + tpl + "}");
        doomed.add("c:alloc:{" + tpl + "}");
        for (var e : redis.<String, String>opsForHash().entries("c:act:{" + tpl + "}").entrySet()) {
            String base = "{" + tpl + "}:" + e.getKey() + ":" + e.getValue();
            doomed.add("c:stk:" + base);
            doomed.add("c:dbi:" + base);
            doomed.add("c:void:" + base);
            doomed.add("c:pend:" + base);
        }
        redis.delete(doomed);

        // -7 → ENSURE（全世代口径：活跃+封账真代，排除历史行）→ 领券恢复
        assertThat(receiveQuietly(USER.incrementAndGet(), "ens2-heal", tpl)).isEqualTo("OK");

        // 巡检一轮：必须干净——旧口径只重建活跃行 → E2b 永久破缺（c:alloc 缺封账真代那一块）
        assertThat(reconcileService.checkOne(tpl))
                .as("灾后重建后巡检必须干净（E2b 镜像口径含封账真代、E3a 含历史行）").isFalse();
        var gc = stockGateway.conservationAll(tpl, 300);
        assertThat(gc.alloc()).as("c:alloc = 镜像口径 Σquota（10+10+5）").isEqualTo(25L);
        long dbiSum = gc.buckets().stream().mapToLong(b -> b.dbi()).sum();
        assertThat(dbiSum).as("Σdbi = 封账真代 used(10+10) + 活跃代 used(2) = 22").isEqualTo(22L);
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
