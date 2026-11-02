package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.StockAdjustRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.C1StockReconcileService;
import com.newcoupon.application.service.CouponIssueTaskProcessor;
import com.newcoupon.application.service.CouponIssueTaskService;
import com.newcoupon.application.service.CouponIssueTxService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.StockLayoutChangedException;
import com.newcoupon.application.service.StockReshardService;
import com.newcoupon.application.service.StockReshardService.ReshardResult;
import com.newcoupon.application.service.StockReshardService.RollbackResult;
import com.newcoupon.application.service.StockShardAdjustService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TemplateLifecycleService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockAdjustLogMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.mapper.TemplateMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import com.newcoupon.infrastructure.persistence.po.CouponStockAdjustLogPO;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import com.newcoupon.infrastructure.persistence.po.CouponTemplatePO;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 离线 reshard / rollback 的真实库契约（layout_version Slice B）：
 *
 * <ul>
 *   <li>N→M 只搬剩余额度：全局 Σquota/Σused 不变，旧行退役为 status=1、quota=used；</li>
 *   <li>stale v1 在 v2 之后不能扣库存；PENDING 任务存活并在 v2 执行；</li>
 *   <li>reshard 后 200 并发仍不超卖；</li>
 *   <li>rollback 仅在 v2 未出券时可恢复；已出券拒绝并恢复 v2 可卖；</li>
 *   <li>adjust/freeze 只动 active version；per-version reconcile 通过。</li>
 * </ul>
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.stock.shard-count=8",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class StockReshardIntegrationTest extends ShardedIntegrationTestSupport {

    private static final AtomicLong USER = new AtomicLong(600_000);

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Autowired private TemplateAppService templateAppService;
    @Autowired private TemplateRepository templateRepository;
    @Autowired private TemplateMapper templateMapper;
    @Autowired private ReceiveAppService receiveAppService;
    @Autowired private CouponIssueTxService issueTxService;
    @Autowired private CouponStockShardMapper shardMapper;
    @Autowired private CouponStockAdjustLogMapper adjustLogMapper;
    @Autowired private StockReshardService reshardService;
    @Autowired private StockShardAdjustService adjustService;
    @Autowired private TemplateLifecycleService lifecycleService;
    @Autowired private C1StockReconcileService reconcileService;
    @Autowired private CouponIssueTaskService taskService;
    @Autowired private CouponIssueTaskProcessor taskProcessor;
    @Autowired private CouponIssueTaskMapper taskMapper;
    @Autowired private javax.sql.DataSource dataSource;

    // ------------------------------------------------------------------
    // 正向 reshard
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("N→M：全局 Σquota/Σused 不变；旧行退役 quota=used；stale v1 不扣；对账通过")
    void reshardPreservesTotalsAndRetiresOldRows() {
        long templateId = createAndPublish(53, 1);
        for (int i = 0; i < 3; i++) {
            assertThat(receive(USER.incrementAndGet(), "reshard-used-" + i, templateId))
                    .isEqualTo("OK");
        }
        CouponTemplate staleV1 = templateRepository.findById(templateId).orElseThrow();
        assertThat(staleV1.getStockLayoutVersion()).isEqualTo(1);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(3);

        String requestId = "reshard-" + UUID.randomUUID();
        ReshardResult result = reshardService.reshard(templateId, 4, requestId, "it");

        assertThat(result.fromVersion()).isEqualTo(1);
        assertThat(result.toVersion()).isEqualTo(2);
        assertThat(result.fromShards()).isEqualTo(8);
        assertThat(result.toShards()).isEqualTo(4);
        assertThat(result.totalCount()).isEqualTo(53);
        assertThat(result.usedCount()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(50);
        assertThat(result.active()).isTrue();
        assertThat(result.idempotent()).isFalse();

        CouponTemplatePO po = templateMapper.selectById(templateId);
        assertThat(po.getStockLayoutVersion()).isEqualTo(2);
        assertThat(po.getStockShardCount()).isEqualTo(4);
        assertThat(shardMapper.sumQuota(templateId)).isEqualTo(53);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(3);

        List<CouponStockShardPO> v1 = shardMapper.selectByTemplateVersion(templateId, 1);
        assertThat(v1).hasSize(8);
        assertThat(v1).allMatch(r -> Integer.valueOf(1).equals(r.getStatus()));
        assertThat(v1).allMatch(r -> r.getQuota().equals(r.getUsed()));
        assertThat(shardMapper.sumQuotaVersion(templateId, 1)).isEqualTo(3);
        assertThat(shardMapper.sumUsedVersion(templateId, 1)).isEqualTo(3);

        List<CouponStockShardPO> v2 = shardMapper.selectByTemplateVersion(templateId, 2);
        assertThat(v2).hasSize(4);
        assertThat(v2).allMatch(r -> Integer.valueOf(2).equals(r.getLayoutVersion()));
        assertThat(v2).allMatch(r -> Integer.valueOf(0).equals(r.getStatus()));
        assertThat(v2).allMatch(r -> Long.valueOf(0L).equals(r.getReserved()));
        assertThat(v2.stream().mapToLong(CouponStockShardPO::getQuota).sum()).isEqualTo(50);
        assertThat(shardMapper.sumUsedVersion(templateId, 2)).isZero();

        // stale v1：事务 fence 必须在计数/扣库存前拒绝
        long staleUser = USER.incrementAndGet();
        assertThatThrownBy(() -> issueTxService.issueC1(
                new ReceiveRequest("stale-after-reshard", staleUser, templateId),
                staleV1, 1, staleV1.getStockLayoutVersion(), 0,
                LocalDateTime.now(), LocalDate.now()))
                .isInstanceOf(StockLayoutChangedException.class);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(3);
        assertThat(shardMapper.sumUsedVersion(templateId, 2)).isZero();

        CouponStockAdjustLogPO audit = adjustLogMapper.selectByRequest(templateId, requestId);
        assertThat(audit).isNotNull();
        assertThat(audit.getReason()).startsWith("RESHARD v1->v2");

        assertThat(reconcileService.auditAll())
                .as("reshard 后 per-version reconcile 必须通过").isZero();

        ReshardResult replay = reshardService.reshard(templateId, 4, requestId, "it");
        assertThat(replay.idempotent()).isTrue();
        assertThat(replay.toVersion()).isEqualTo(2);
        assertThatThrownBy(() -> reshardService.reshard(templateId, 5, requestId, "it"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    @Timeout(120)
    @DisplayName("PENDING 任务存活 reshard，并在新版本上成功发券")
    void pendingTaskSurvivesReshardAndRunsOnNewVersion() throws Exception {
        long templateId = createAndPublish(20, 1);
        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();
        long userId = USER.incrementAndGet();
        CouponIssueTaskPO task = taskService.accept(
                new ReceiveRequest("pending-reshard-" + userId, userId, templateId),
                template, LocalDate.now(), 1);
        assertThat(taskMapper.selectById(task.getId()).getStatus())
                .isEqualTo(CouponIssueTaskPO.STATUS_PENDING);

        reshardService.reshard(templateId, 4, "reshard-pending-" + UUID.randomUUID(), "it");
        assertThat(taskMapper.selectById(task.getId()).getStatus())
                .as("软排空只等 PROCESSING，PENDING 必须保留").isEqualTo(CouponIssueTaskPO.STATUS_PENDING);

        String owner = "reshard-test-worker";
        lease(task.getId(), owner);
        taskProcessor.process(task, owner);

        CouponIssueTaskPO done = taskMapper.selectById(task.getId());
        assertThat(done.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        assertThat(done.getCouponId()).isNotNull();
        assertThat(shardMapper.sumUsedVersion(templateId, 2)).isEqualTo(1);
        assertThat(shardMapper.sumUsedVersion(templateId, 1)).isZero();
    }

    @Test
    @Timeout(60)
    @DisplayName("冻结窗口：异步任务退回 PENDING（5000），不标 STOCK_OUT")
    void frozenLayoutRetriesTaskInsteadOfStockOut() throws Exception {
        long templateId = createAndPublish(20, 1);
        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();
        long userId = USER.incrementAndGet();
        CouponIssueTaskPO task = taskService.accept(
                new ReceiveRequest("frozen-retry-" + userId, userId, templateId),
                template, LocalDate.now(), 1);
        lifecycleService.freeze(templateId);

        String owner = "frozen-test-worker";
        lease(task.getId(), owner);
        taskProcessor.process(task, owner);

        CouponIssueTaskPO retried = taskMapper.selectById(task.getId());
        assertThat(retried.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_PENDING);
        assertThat(retried.getRetryCount()).isEqualTo(1);
        assertThat(retried.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(shardMapper.sumUsed(templateId)).isZero();
        lifecycleService.unfreeze(templateId);
    }

    @Test
    @Timeout(180)
    @DisplayName("reshard 后 200 并发抢 50：成功恰好 50，新版本 Σused=50，不超卖")
    void concurrentReceiveAfterReshardNoOversell() throws Exception {
        long templateId = createAndPublish(50, 1);
        reshardService.reshard(templateId, 4, "reshard-conc-" + UUID.randomUUID(), "it");

        int tasks = 200;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = IntStream.range(0, tasks)
                    .mapToObj(i -> pool.submit(() -> {
                        go.await();
                        return receive(USER.incrementAndGet(), "reshard-conc-" + i, templateId);
                    }))
                    .toList();
            go.countDown();
            List<String> results = futures.stream().map(f -> {
                try {
                    return f.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return "EX:" + e.getClass().getSimpleName();
                }
            }).toList();
            long success = results.stream().filter("OK"::equals).count();
            var failures = results.stream().filter(r -> !"OK".equals(r))
                    .collect(java.util.stream.Collectors.groupingBy(
                            r -> r, java.util.stream.Collectors.counting()));
            assertThat(success).as("失败分布=" + failures).isEqualTo(50);
            assertThat(failures.keySet()).allMatch(k -> "3001".equals(k) || k.startsWith("EX:"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(shardMapper.sumUsedVersion(templateId, 2)).isEqualTo(50);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(50);
        assertThat(reconcileService.auditAll()).isZero();
    }

    // ------------------------------------------------------------------
    // rollback
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("rollback（v2 未出券）：删除 v2、恢复 v1 原额度/可卖，全局守恒")
    void rollbackBeforeNewVersionIssuedRestoresOldLayout() {
        long templateId = createAndPublish(20, 1);
        reshardService.reshard(templateId, 4, "reshard-rb-" + UUID.randomUUID(), "it");

        String rollbackId = "rollback-" + UUID.randomUUID();
        RollbackResult result = reshardService.rollback(templateId, 2, rollbackId, "it");

        assertThat(result.fromVersion()).isEqualTo(2);
        assertThat(result.toVersion()).isEqualTo(1);
        assertThat(result.fromShards()).isEqualTo(4);
        assertThat(result.toShards()).isEqualTo(8);
        assertThat(result.active()).isTrue();
        assertThat(result.idempotent()).isFalse();

        CouponTemplatePO po = templateMapper.selectById(templateId);
        assertThat(po.getStockLayoutVersion()).isEqualTo(1);
        assertThat(po.getStockShardCount()).isEqualTo(8);
        assertThat(shardMapper.countByTemplateVersion(templateId, 2)).isZero();
        List<CouponStockShardPO> v1 = shardMapper.selectByTemplateVersion(templateId, 1);
        assertThat(v1).hasSize(8);
        assertThat(v1).allMatch(r -> Integer.valueOf(0).equals(r.getStatus()));
        assertThat(v1.stream().mapToLong(CouponStockShardPO::getQuota).sum()).isEqualTo(20);
        assertThat(shardMapper.sumQuota(templateId)).isEqualTo(20);
        assertThat(shardMapper.sumUsed(templateId)).isZero();

        CouponStockAdjustLogPO audit = adjustLogMapper.selectByRequest(templateId, rollbackId);
        assertThat(audit.getReason()).startsWith("ROLLBACK v2->v1");

        RollbackResult replay = reshardService.rollback(templateId, 2, rollbackId, "it");
        assertThat(replay.idempotent()).isTrue();
        assertThat(replay.toVersion()).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("rollback（v2 已出券）：拒绝回滚，恢复 v2 可卖，库存/账本不变")
    void rollbackAfterNewVersionIssuedRefusedAndKeepsServing() {
        long templateId = createAndPublish(20, 1);
        reshardService.reshard(templateId, 4, "reshard-rb2-" + UUID.randomUUID(), "it");
        long firstUser = USER.incrementAndGet();
        assertThat(receive(firstUser, "reshard-rb2-first", templateId)).isEqualTo("OK");
        assertThat(shardMapper.sumUsedVersion(templateId, 2)).isEqualTo(1);

        assertThatThrownBy(() -> reshardService.rollback(
                templateId, 2, "rollback-refused-" + UUID.randomUUID(), "it"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        CouponTemplatePO po = templateMapper.selectById(templateId);
        assertThat(po.getStockLayoutVersion()).isEqualTo(2);
        assertThat(shardMapper.countByTemplateVersion(templateId, 1)).isEqualTo(8);
        assertThat(shardMapper.sumUsedVersion(templateId, 2)).isEqualTo(1);
        assertThat(shardMapper.countActive(templateId, 2))
                .as("回滚被拒后必须恢复 v2 可卖，不能留下冻结").isGreaterThan(0);
        assertThat(receive(USER.incrementAndGet(), "reshard-rb2-second", templateId))
                .isEqualTo("OK");
    }

    // ------------------------------------------------------------------
    // adjust / lifecycle
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("adjust/freeze 只动 active version：v2 额度/状态变化，v1 退役行不动")
    void adjustAndFreezeOnlyTouchActiveVersion() {
        long templateId = createAndPublish(30, 1);
        reshardService.reshard(templateId, 4, "reshard-adjust-" + UUID.randomUUID(), "it");

        adjustService.adjust(templateId, new StockAdjustRequest(
                "reshard-adjust-" + UUID.randomUUID(), 30L, 40L, "reshard test", "it"));
        assertThat(shardMapper.sumQuota(templateId)).isEqualTo(40);
        assertThat(shardMapper.sumQuotaVersion(templateId, 2)).isEqualTo(40);
        assertThat(shardMapper.sumQuotaVersion(templateId, 1)).isZero();
        List<CouponStockShardPO> v1Before = shardMapper.selectByTemplateVersion(templateId, 1);
        assertThat(v1Before).allMatch(r -> r.getQuota().equals(r.getUsed()));

        lifecycleService.freeze(templateId);
        assertThat(shardMapper.countActive(templateId, 2)).isZero();
        assertThat(shardMapper.selectByTemplateVersion(templateId, 1))
                .allMatch(r -> Integer.valueOf(1).equals(r.getStatus()));
        lifecycleService.unfreeze(templateId);
        assertThat(shardMapper.countActive(templateId, 2)).isEqualTo(4);
        assertThat(reconcileService.auditAll()).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long createAndPublish(long total, int perUserLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(
                new TemplateCreateRequest("reshard 验证券", 1201L, 1, 1000L, null, 10_000L,
                        total, perUserLimit, 1, LocalDateTime.now().minusDays(1),
                        LocalDateTime.now().plusDays(30), null, 0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }

    private String receive(long userId, String requestId, long templateId) {
        try {
            receiveAppService.receive(new ReceiveRequest(requestId, userId, templateId));
            return "OK";
        } catch (BizException e) {
            return String.valueOf(e.getErrorCode().getCode());
        }
    }

    private void lease(long taskId, String owner) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE coupon_issue_task SET status = 1, lease_owner = ?, lease_until = ? "
                             + "WHERE id = ?")) {
            ps.setString(1, owner);
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now().plusMinutes(5)));
            ps.setLong(3, taskId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }
}
