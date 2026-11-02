package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveAsyncResult;
import com.newcoupon.application.dto.ReceiveAsyncStatusResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.CouponIssueGateCompensationJob;
import com.newcoupon.application.service.CouponIssueTaskBatchTxService.BatchItem;
import com.newcoupon.application.service.CouponIssueTaskCleanupJob;
import com.newcoupon.application.service.CouponIssueTaskRawBulkWriter;
import com.newcoupon.application.service.CouponIssueTaskService;
import com.newcoupon.application.service.CouponIssueTaskWorker;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.ReceiveAsyncAppService;
import com.newcoupon.application.service.StockShardPlan;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * worker 真落库契约（ADR-0019）：
 * <ul>
 *   <li>任务 → C1 分片 → 券/事件/outbox/过期任务 → SUCCESS 同一事务；</li>
 *   <li>模板日限/每人日限在事务内条件更新，超出后任务 REJECTED，不写券；</li>
 *   <li>重复执行不会重复发券。</li>
 * </ul>
 *
 * <p>worker 的 @Scheduled 初始延迟拉长到 1 小时，测试手动调用 runOnce，避免后台线程抢任务。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.stock.shard-count=8",
        "coupon.issue.async-enabled=true",
        "coupon.issue.async-template-ids=*",
        "coupon.issue.worker.enabled=true",
        "coupon.issue.worker.raw-bulk-enabled=true",
        "coupon.issue.worker.initial-delay-ms=3600000",
        "coupon.issue.worker.fixed-delay-ms=3600000",
        "coupon.issue.worker.batch-size=10",
        "coupon.issue.gate-compensation.enabled=true",
        "coupon.issue.gate-compensation.initial-delay-ms=3600000",
        "coupon.issue.gate-compensation.fixed-delay-ms=3600000",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class AsyncIssueWorkerIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger USER = new AtomicInteger(700_000);
    private static final String APP_ID = "worker-" + Long.toHexString(System.nanoTime());
    private final List<Long> templates = new ArrayList<>();

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private ReceiveAsyncAppService receiveAsyncAppService;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private CouponIssueTaskService taskService;
    @Autowired
    private CouponIssueTaskMapper taskMapper;
    @Autowired
    private CouponIssueTaskWorker worker;
    @Autowired
    private CouponIssueTaskCleanupJob cleanupJob;
    @Autowired
    private UserCouponRepository userCouponRepository;
    @Autowired
    private CouponStockShardMapper shardMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CouponIssueTaskRawBulkWriter rawBulkWriter;
    @Autowired
    @Qualifier("rawBulkDataSource")
    private DataSource rawBulkDataSource;
    @Autowired
    private CouponIssueGateCompensationJob gateCompensationJob;
    @Autowired
    private RedisIssueGate issueGate;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void bindAppContext() {
        RequestContext.set(new RequestContext(APP_ID, "worker-test", Long.MAX_VALUE, false));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM coupon_issue_task WHERE app_id = ?", APP_ID);
        for (long templateId : templates) {
            jdbc.update("DELETE FROM coupon_user_daily_counter WHERE template_id = ?", templateId);
            jdbc.update("DELETE FROM coupon_daily_counter WHERE template_id = ?", templateId);
            // 保留 coupon_stock_shard：模板行和 user_coupon 行仍在，这三张表必须一起自洽。
            // 只删库存行会让已发布模板变成 Σquota=0、Σused=0，而券行还在；
            // 其它测试的 C1 全量审计（扫描所有已发布模板）会把它当成漂移。
        }
        RequestContext.clear();
    }

    private long createC1Template(long total, int perUserLimit,
                                  long dailyLimit, int perUserDailyLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "worker验证券", 3001L, 1, 1000L, null, 10000L, total, perUserLimit,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                dailyLimit, perUserDailyLimit));
        templateAppService.publish(created.id());
        templates.add(created.id());
        return created.id();
    }

    private CouponIssueTaskPO manualTask(long templateId, long userId, String requestId) {
        return manualTask(templateId, userId, requestId, LocalDate.now());
    }

    private CouponIssueTaskPO manualTask(long templateId, long userId, String requestId,
                                         LocalDate statDay) {
        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();
        CouponIssueTaskPO task = taskService.accept(new ReceiveRequest(requestId, userId, templateId),
                template, statDay, 1);
        // 避免 WSL 时钟回拨把刚创建的任务 next_scan_time 推到 worker 当前时间之后。
        jdbc.update("UPDATE coupon_issue_task SET next_scan_time = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0)), task.getId());
        return task;
    }

    /** 反复领取直到本用例任务到终态，避免其它用例的待领取任务占满每轮 claim 窗口。 */
    private void drainOwnTasks(List<Long> taskIds) {
        for (int round = 0; round < 20; round++) {
            boolean allTerminal = taskIds.stream()
                    .map(taskMapper::selectById)
                    .allMatch(task -> task != null && task.getStatus() != null
                            && (task.getStatus() == CouponIssueTaskPO.STATUS_SUCCESS
                            || task.getStatus() == CouponIssueTaskPO.STATUS_REJECTED
                            || task.getStatus() == CouponIssueTaskPO.STATUS_DEAD));
            if (allTerminal) {
                return;
            }
            worker.runOnce();
        }
    }

    private long templateDailyUsed(long templateId) {
        return templateDailyUsed(templateId, LocalDate.now());
    }

    private long templateDailyUsed(long templateId, LocalDate day) {
        Long used = jdbc.queryForObject(
                "SELECT COALESCE(MAX(used), 0) FROM coupon_daily_counter "
                        + "WHERE template_id = ? AND stat_day = ?",
                Long.class, templateId, java.sql.Date.valueOf(day));
        return used == null ? 0 : used;
    }

    private long userDailyUsed(long userId, long templateId) {
        Long used = jdbc.queryForObject(
                "SELECT COALESCE(MAX(used), 0) FROM coupon_user_daily_counter "
                        + "WHERE user_id = ? AND template_id = ? AND stat_day = ?",
                Long.class, userId, templateId, java.sql.Date.valueOf(LocalDate.now()));
        return used == null ? 0 : used;
    }

    @Test
    @DisplayName("worker：任务→C1 券/事件/outbox/过期任务→SUCCESS；重复执行不再发")
    void workerIssuesCouponAndIsIdempotent() {
        long templateId = createC1Template(10, 2, 0, 0);
        long userId = USER.incrementAndGet();
        String requestId = "worker-ok-" + UUID.randomUUID().toString().substring(0, 8);
        ReceiveAsyncResult accepted = receiveAsyncAppService.receive(
                new ReceiveRequest(requestId, userId, templateId));

        assertThat(taskMapper.selectById(accepted.taskId()).getStatus())
                .isEqualTo(CouponIssueTaskPO.STATUS_PENDING);
        assertThat(worker.runOnce()).isGreaterThanOrEqualTo(1);

        CouponIssueTaskPO task = taskMapper.selectById(accepted.taskId());
        assertThat(task.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        assertThat(task.getCouponId()).isNotNull();
        assertThat(userCouponRepository.findByIdForUser(task.getCouponId(), userId)).isPresent();
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);

        ReceiveAsyncStatusResult status = receiveAsyncAppService.status(APP_ID, userId, requestId);
        assertThat(status.status()).isEqualTo("SUCCESS");
        assertThat(status.couponId()).isEqualTo(task.getCouponId());

        worker.runOnce();   // 再跑一轮：本任务已是 SUCCESS，不能再发
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("模板日限：worker 事务内硬限制，第二个任务 REJECTED，不写券")
    void templateDailyLimitIsHardInWorker() {
        long templateId = createC1Template(10, 1, 1, 0);
        long firstUser = USER.incrementAndGet();
        long secondUser = USER.incrementAndGet();
        CouponIssueTaskPO first = manualTask(templateId, firstUser, "worker-day-1");
        CouponIssueTaskPO second = manualTask(templateId, secondUser, "worker-day-2");

        worker.runOnce();

        assertThat(taskMapper.selectById(first.getId()).getStatus())
                .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        CouponIssueTaskPO rejected = taskMapper.selectById(second.getId());
        assertThat(rejected.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_REJECTED);
        assertThat(rejected.getErrorCode()).isEqualTo(3003);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("每人日限：worker 事务内硬限制，第二个任务 REJECTED，不写券")
    void userDailyLimitIsHardInWorker() {
        long templateId = createC1Template(10, 2, 0, 1);
        long userId = USER.incrementAndGet();
        CouponIssueTaskPO first = manualTask(templateId, userId, "worker-ud-1");
        CouponIssueTaskPO second = manualTask(templateId, userId, "worker-ud-2");

        worker.runOnce();

        assertThat(taskMapper.selectById(first.getId()).getStatus())
                .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        CouponIssueTaskPO rejected = taskMapper.selectById(second.getId());
        assertThat(rejected.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_REJECTED);
        assertThat(rejected.getErrorCode()).isEqualTo(3002);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("同步与异步共用模板日计数：同步领 1 张后 worker 第二张 3003")
    void syncAndWorkerShareTemplateDailyLimit() {
        long templateId = createC1Template(10, 1, 1, 0);
        long syncUser = USER.incrementAndGet();
        long workerUser = USER.incrementAndGet();

        receiveAppService.receive(new ReceiveRequest("mix-sync-td", syncUser, templateId));
        CouponIssueTaskPO task = manualTask(templateId, workerUser, "mix-worker-td");
        drainOwnTasks(List.of(task.getId()));

        CouponIssueTaskPO rejected = taskMapper.selectById(task.getId());
        assertThat(rejected.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_REJECTED);
        assertThat(rejected.getErrorCode()).isEqualTo(3003);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
        assertThat(templateDailyUsed(templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("同步与异步共用每人日计数：同一用户跨路径第二张 3002")
    void syncAndWorkerShareUserDailyLimit() {
        long templateId = createC1Template(10, 5, 0, 1);
        long userId = USER.incrementAndGet();

        receiveAppService.receive(new ReceiveRequest("mix-sync-ud", userId, templateId));
        CouponIssueTaskPO task = manualTask(templateId, userId, "mix-worker-ud");
        drainOwnTasks(List.of(task.getId()));

        CouponIssueTaskPO rejected = taskMapper.selectById(task.getId());
        assertThat(rejected.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_REJECTED);
        assertThat(rejected.getErrorCode()).isEqualTo(3002);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
        assertThat(userDailyUsed(userId, templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("同步已发券、异步同 requestId 重试：日限拒绝前回查并补 SUCCESS")
    void syncThenAsyncSameRequestIsIdempotentSuccess() {
        long templateId = createC1Template(10, 5, 0, 1);
        long userId = USER.incrementAndGet();
        String requestId = "mix-idem-" + UUID.randomUUID();

        var sync = receiveAppService.receive(new ReceiveRequest(requestId, userId, templateId));
        CouponIssueTaskPO task = manualTask(templateId, userId, requestId);
        drainOwnTasks(List.of(task.getId()));

        CouponIssueTaskPO done = taskMapper.selectById(task.getId());
        assertThat(done.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        assertThat(done.getCouponId()).isEqualTo(sync.couponId());
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
        assertThat(userDailyUsed(userId, templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("跨天：worker 用任务受理日 stat_day，不用执行日")
    void workerUsesAcceptanceStatDay() {
        long templateId = createC1Template(10, 5, 1, 0);
        LocalDate yesterday = LocalDate.now().minusDays(1);

        CouponIssueTaskPO yesterdayTask = manualTask(
                templateId, USER.incrementAndGet(), "cross-day-y", yesterday);
        drainOwnTasks(List.of(yesterdayTask.getId()));

        assertThat(taskMapper.selectById(yesterdayTask.getId()).getStatus())
                .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        assertThat(templateDailyUsed(templateId, yesterday)).as("受理日计数=1").isEqualTo(1);
        assertThat(templateDailyUsed(templateId, LocalDate.now())).as("执行日计数应为 0").isZero();

        CouponIssueTaskPO todayTask = manualTask(
                templateId, USER.incrementAndGet(), "cross-day-t", LocalDate.now());
        drainOwnTasks(List.of(todayTask.getId()));
        assertThat(taskMapper.selectById(todayTask.getId()).getStatus())
                .as("今天仍有独立额度，第二张应成功")
                .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        assertThat(templateDailyUsed(templateId, LocalDate.now())).isEqualTo(1);
    }

    @Test
    @DisplayName("同步已发券、异步同 requestId 重试且库存已满：补 SUCCESS 而不是 3001")
    void syncThenAsyncStockOutIsIdempotentSuccess() {
        long templateId = createC1Template(1, 5, 0, 0);
        long userId = USER.incrementAndGet();
        String requestId = "mix-stock-idem-" + UUID.randomUUID();

        var sync = receiveAppService.receive(new ReceiveRequest(requestId, userId, templateId));
        CouponIssueTaskPO task = manualTask(templateId, userId, requestId);
        drainOwnTasks(List.of(task.getId()));

        CouponIssueTaskPO done = taskMapper.selectById(task.getId());
        assertThat(done.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        assertThat(done.getCouponId()).isEqualTo(sync.couponId());
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("批量快路径：同一轮领取混有两个模板，券归属不串")
    void batchPathKeepsTemplateOwnership() {
        long first = createC1Template(20, 1, 0, 0);
        long second = createC1Template(20, 1, 0, 0);
        List<long[]> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CouponIssueTaskPO t1 = manualTask(first, USER.incrementAndGet(), "batch-mix-a-" + i);
            CouponIssueTaskPO t2 = manualTask(second, USER.incrementAndGet(), "batch-mix-b-" + i);
            expected.add(new long[]{t1.getId(), first});
            expected.add(new long[]{t2.getId(), second});
        }

        worker.runOnce();

        for (long[] pair : expected) {
            CouponIssueTaskPO task = taskMapper.selectById(pair[0]);
            assertThat(task.getStatus()).as("task=%s", pair[0])
                    .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
            assertThat(task.getTemplateId()).as("批量路径不得把模板 A 的券发成模板 B")
                    .isEqualTo(pair[1]);
        }
        assertThat(userCouponRepository.countByTemplate(first)).isEqualTo(5);
        assertThat(userCouponRepository.countByTemplate(second)).isEqualTo(5);
        assertThat(shardMapper.sumUsed(first)).isEqualTo(5);
        assertThat(shardMapper.sumUsed(second)).isEqualTo(5);
    }

    @Test
    @DisplayName("批量分片事务撞库存上限：整组回滚后逐条重跑，仍不超发")
    void batchPathFallsBackOnShardSelloutWithoutOversell() {
        long templateId = createC1Template(5, 1, 0, 0);
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            taskIds.add(manualTask(templateId, USER.incrementAndGet(), "batch-sellout-" + i).getId());
        }

        worker.runOnce();

        long success = taskIds.stream()
                .map(id -> taskMapper.selectById(id).getStatus())
                .filter(s -> s == CouponIssueTaskPO.STATUS_SUCCESS)
                .count();
        long rejected = taskIds.stream()
                .map(id -> taskMapper.selectById(id))
                .filter(t -> t.getStatus() == CouponIssueTaskPO.STATUS_REJECTED)
                .count();
        assertThat(success).as("库存 5：最多成功 5 张").isEqualTo(5);
        assertThat(rejected).as("其余任务必须落终态 REJECTED，不能悬空").isEqualTo(5);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(5);
        assertThat(userCouponRepository.countByTemplate(templateId)).isEqualTo(5);
    }

    @Test
    @DisplayName("批量重跑的崩溃窗口：券已发但任务未成功，重试补 SUCCESS 而非误判限领")
    void retryAfterCouponCommittedButTaskNotMarkedIsIdempotentSuccess() {
        long templateId = createC1Template(10, 1, 0, 0);
        long userId = USER.incrementAndGet();
        CouponIssueTaskPO task = manualTask(templateId, userId, "raw-idem-retry");

        worker.runOnce();
        assertThat(taskMapper.selectById(task.getId()).getStatus())
                .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);

        // 模拟“券/库存已提交，任务状态没提交”的崩溃窗口：把任务退回可领取。
        jdbc.update("UPDATE coupon_issue_task SET status = 0, coupon_id = NULL, lease_owner = NULL, "
                + "lease_until = NULL, next_scan_time = NOW() WHERE id = ?", task.getId());

        worker.runOnce();

        assertThat(taskMapper.selectById(task.getId()).getStatus())
                .as("已发过同一 requestId 的券，重试必须补 SUCCESS")
                .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
        assertThat(userCouponRepository.countByTemplate(templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("裸写快路径：真实物理表的券/过期任务/事件/outbox/任务状态一致")
    void rawBulkWriterWritesPhysicalTables() {
        long templateId = createC1Template(10, 1, 0, 0);
        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();
        String owner = "raw-write-owner";
        List<BatchItem> items = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long userId = USER.incrementAndGet();
            CouponIssueTaskPO task = manualTask(templateId, userId, "raw-write-" + UUID.randomUUID());
            // writer 的 markSuccess 以 status=1 + lease_owner 为条件；模拟 worker 已领取。
            jdbc.update("UPDATE coupon_issue_task SET status = 1, lease_owner = ?, lease_until = ? "
                            + "WHERE id = ?", owner,
                    Timestamp.valueOf(LocalDateTime.now().plusMinutes(1)), task.getId());
            items.add(new BatchItem(task, template, 1, template.getStockLayoutVersion(),
                    StockShardPlan.route(userId, template.getStockShardCount())));
            userIds.add(userId);
            taskIds.add(task.getId());
        }

        rawBulkWriter.write(items, owner);

        JdbcTemplate raw = new JdbcTemplate(rawBulkDataSource);
        for (int i = 0; i < userIds.size(); i++) {
            long userId = userIds.get(i);
            int userShard = (int) Math.floorMod(userId, 64);
            CouponIssueTaskPO task = taskMapper.selectById(taskIds.get(i));
            assertThat(task.getStatus()).as("task=%s", task.getId())
                    .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
            assertThat(task.getCouponId()).isNotNull();
            // P2 回归：批量成功与单任务 markSuccess 一样标记"gate 已签发"，补偿任务永不释放成功任务
            assertThat(task.getGateReleaseState()).as("task=%s 成功任务 gate_release_state", task.getId())
                    .isEqualTo(CouponIssueTaskPO.GATE_ISSUED);
            assertThat(raw.queryForObject(
                    "SELECT COUNT(*) FROM user_coupon_" + userShard
                            + " WHERE user_id = ? AND template_id = ?",
                    Long.class, userId, templateId))
                    .as("user_coupon_%s", userShard).isEqualTo(1L);
            assertThat(raw.queryForObject(
                    "SELECT COUNT(*) FROM coupon_expire_task_" + userShard
                            + " WHERE user_id = ?", Long.class, userId))
                    .as("coupon_expire_task_%s", userShard).isEqualTo(1L);
        }
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(5);
        assertThat(userCouponRepository.countByTemplate(templateId)).isEqualTo(5);
        assertThat(raw.queryForObject(
                "SELECT COUNT(*) FROM coupon_event WHERE template_id = ?", Long.class, templateId))
                .isEqualTo(5L);
        assertThat(raw.queryForObject(
                "SELECT COUNT(*) FROM coupon_outbox o JOIN coupon_event e ON o.event_no = e.event_no "
                        + "WHERE e.template_id = ?", Long.class, templateId))
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("租约回收：PROCESSING 且 lease 过期的任务会被下一轮重新领取")
    void expiredLeaseIsReclaimed() {
        long templateId = createC1Template(10, 1, 0, 0);
        long userId = USER.incrementAndGet();
        CouponIssueTaskPO task = manualTask(templateId, userId, "lease-reclaim");

        jdbc.update("UPDATE coupon_issue_task SET status = 1, lease_owner = 'dead-worker', "
                        + "lease_until = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(5)), task.getId());

        worker.runOnce();

        CouponIssueTaskPO reclaimed = taskMapper.selectById(task.getId());
        assertThat(reclaimed.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
    }

    @Test
    @DisplayName("gate 补偿：日限拒绝回加库存 gate，成功任务不释放")
    void gateCompensationReleasesRejectedButNotSuccess() {
        // 受理时 gate 看到 dailyLimit=0，两个任务都能进 WAL；worker 执行前再把 DB 日限改成 1，
        // 制造“worker 侧 3003 拒绝”的现场（gate 快照仍是 0，不会被 gate 计数挡住）。
        long templateId = createC1Template(10, 1, 0, 0);
        long firstUser = USER.incrementAndGet();
        long secondUser = USER.incrementAndGet();
        String firstReq = "gate-rel-1-" + UUID.randomUUID();
        String secondReq = "gate-rel-2-" + UUID.randomUUID();

        ReceiveAsyncResult first = receive(templateId, firstUser, firstReq);
        ReceiveAsyncResult second = receive(templateId, secondUser, secondReq);
        jdbc.update("UPDATE coupon_template SET daily_limit = 1 WHERE id = ?", templateId);
        assertThat(issueGate.currentGate(templateId)).isEqualTo(8L);

        drainOwnTasks(List.of(first.taskId(), second.taskId()));
        CouponIssueTaskPO rejected = taskMapper.selectByUniqueKey(APP_ID, secondUser, secondReq);
        assertThat(rejected.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_REJECTED);
        assertThat(rejected.getErrorCode()).isEqualTo(3003);
        assertThat(issueGate.currentGate(templateId)).isEqualTo(8L);

        forceGateDue(rejected.getId());
        gateCompensationJob.runOnce();

        assertThat(issueGate.currentGate(templateId)).isEqualTo(9L);
        assertThat(taskMapper.selectById(rejected.getId()).getGateReleaseState())
                .isEqualTo(CouponIssueTaskPO.GATE_RELEASED);
    }

    @Test
    @DisplayName("gate 补偿：STOCK_OUT 不回加库存 gate")
    void stockOutDoesNotReleaseStockGate() {
        long templateId = createC1Template(1, 1, 0, 0);
        long firstUser = USER.incrementAndGet();
        long secondUser = USER.incrementAndGet();
        String firstReq = "gate-stock-1-" + UUID.randomUUID();

        ReceiveAsyncResult first = receive(templateId, firstUser, firstReq);
        CouponIssueTaskPO second = manualTask(templateId, secondUser,
                "gate-stock-2-" + UUID.randomUUID());
        assertThat(issueGate.currentGate(templateId)).isZero();

        drainOwnTasks(List.of(first.taskId(), second.getId()));
        CouponIssueTaskPO rejected = taskMapper.selectById(second.getId());
        assertThat(rejected.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_REJECTED);
        assertThat(rejected.getErrorCode()).isEqualTo(3001);

        forceGateDue(rejected.getId());
        gateCompensationJob.runOnce();

        assertThat(issueGate.currentGate(templateId)).as("STOCK_OUT 不能把库存 gate 加回来").isZero();
    }

    @Test
    @DisplayName("gate 补偿：DEAD 任务已发券时修复 SUCCESS，不释放")
    void deadTaskWithCouponIsRepairedNotReleased() {
        long templateId = createC1Template(2, 1, 0, 0);
        long userId = USER.incrementAndGet();
        String requestId = "gate-dead-" + UUID.randomUUID();
        ReceiveAsyncResult accepted = receive(templateId, userId, requestId);

        drainOwnTasks(List.of(accepted.taskId()));
        CouponIssueTaskPO success = taskMapper.selectById(accepted.taskId());
        assertThat(success.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        long gateAfterSuccess = issueGate.currentGate(templateId);

        // 模拟“券已提交、任务状态回退/损坏”的窗口。
        jdbc.update("UPDATE coupon_issue_task SET status = 4, coupon_id = NULL, "
                        + "gate_release_state = 0, gate_next_retry_time = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0)), success.getId());

        gateCompensationJob.runOnce();

        CouponIssueTaskPO repaired = taskMapper.selectById(success.getId());
        assertThat(repaired.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
        assertThat(repaired.getCouponId()).isNotNull();
        assertThat(repaired.getGateReleaseState()).isEqualTo(CouponIssueTaskPO.GATE_ISSUED);
        assertThat(issueGate.currentGate(templateId)).isEqualTo(gateAfterSuccess);
    }

    @Test
    @DisplayName("Redis idem 丢失后重复受理：任务不变，gate 不被重复预扣")
    void duplicateAcceptAfterIdemLossDoesNotConsumeGate() {
        long templateId = createC1Template(5, 1, 0, 0);
        long userId = USER.incrementAndGet();
        String requestId = "gate-idem-" + UUID.randomUUID();
        ReceiveAsyncResult first = receive(templateId, userId, requestId);
        assertThat(issueGate.currentGate(templateId)).isEqualTo(4L);

        redis.delete(idemKey(templateId, requestId));
        ReceiveAsyncResult replay = receive(templateId, userId, requestId);

        assertThat(replay.taskId()).isEqualTo(first.taskId());
        assertThat(issueGate.currentGate(templateId)).isEqualTo(4L);
    }

    @Test
    @DisplayName("gate 重建：扣除在途未发券任务")
    void gateRebuildSubtractsInFlightTasks() {
        long templateId = createC1Template(5, 1, 0, 0);
        ReceiveAsyncResult first = receive(templateId, USER.incrementAndGet(),
                "gate-rebuild-1-" + UUID.randomUUID());
        ReceiveAsyncResult second = receive(templateId, USER.incrementAndGet(),
                "gate-rebuild-2-" + UUID.randomUUID());
        assertThat(issueGate.currentGate(templateId)).isEqualTo(3L);

        redis.delete(gateKey(templateId));
        receive(templateId, USER.incrementAndGet(), "gate-rebuild-3-" + UUID.randomUUID());

        // 5 quota - 0 used - 2 in-flight = 3；本次再预扣 1 => 2。
        assertThat(issueGate.currentGate(templateId)).isEqualTo(2L);
        assertThat(taskMapper.selectById(first.taskId())).isNotNull();
        assertThat(taskMapper.selectById(second.taskId())).isNotNull();
    }

    @Test
    @DisplayName("gate=0 但 DB 仍有库存：限频重建后放行，避免假售罄")
    void emptyGateRebuildsFromDbAndAccepts() {
        long templateId = createC1Template(2, 1, 0, 0);
        redis.opsForValue().set(gateKey(templateId), "0");

        ReceiveAsyncResult accepted = receive(templateId, USER.incrementAndGet(),
                "gate-empty-" + UUID.randomUUID());

        assertThat(accepted.taskId()).isNotNull();
        assertThat(issueGate.currentGate(templateId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Redis 错误：gate 非数字时按 DB 重建后仍可受理")
    void malformedGateIsRebuilt() {
        long templateId = createC1Template(3, 1, 0, 0);
        redis.opsForValue().set(gateKey(templateId), "not-a-number");

        ReceiveAsyncResult accepted = receive(templateId, USER.incrementAndGet(),
                "gate-corrupt-" + UUID.randomUUID());

        assertThat(accepted.taskId()).isNotNull();
        assertThat(taskMapper.selectById(accepted.taskId())).isNotNull();
        assertThat(issueGate.currentGate(templateId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("Redis 错误：gate 类型错误时覆盖重建，不把异步入口永久打挂")
    void wrongTypeGateIsRebuilt() {
        long templateId = createC1Template(3, 1, 0, 0);
        redis.opsForHash().put(gateKey(templateId), "x", "y");

        ReceiveAsyncResult accepted = receive(templateId, USER.incrementAndGet(),
                "gate-wrong-type-" + UUID.randomUUID());

        assertThat(accepted.taskId()).isNotNull();
        assertThat(issueGate.currentGate(templateId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("Redis 错误：限领计数偏高时按 DB 修复后放行")
    void highUserLimitCounterIsRepaired() {
        long templateId = createC1Template(5, 1, 0, 0);
        long userId = USER.incrementAndGet();
        LocalDate day = LocalDate.now();
        RedisIssueGate.GateCommand cmd = new RedisIssueGate.GateCommand(
                templateId, APP_ID, userId, "gate-counter-" + UUID.randomUUID(),
                1, 0, 0, day, 600, 120);
        assertThat(issueGate.ensureGate(templateId, 5, Duration.ofMinutes(5))).isTrue();
        issueGate.repairUserTotal(cmd, 0);
        issueGate.ensureSeqAtLeast(cmd, 0);
        redis.opsForValue().set(ulKey(templateId, userId), "99");

        ReceiveAsyncResult accepted = receive(templateId, userId, cmd.requestId());

        assertThat(accepted.taskId()).isNotNull();
        assertThat(redis.opsForValue().get(ulKey(templateId, userId))).isEqualTo("1");
    }

    @Test
    @DisplayName("Redis 错误：限领计数类型错误时按 DB 重建")
    void corruptUserLimitCounterIsRebuilt() {
        long templateId = createC1Template(5, 1, 0, 0);
        long userId = USER.incrementAndGet();
        issueGate.resetGate(templateId, 5, Duration.ofMinutes(5));
        redis.opsForHash().put(ulKey(templateId, userId), "x", "y");

        ReceiveAsyncResult accepted = receive(templateId, userId,
                "gate-counter-corrupt-" + UUID.randomUUID());

        assertThat(accepted.taskId()).isNotNull();
        assertThat(redis.opsForValue().get(ulKey(templateId, userId))).isEqualTo("1");
    }

    @Test
    @DisplayName("Redis 错误：限领计数缺失时按 DB 重建，而不是当 0 静默多放")
    void missingUserLimitCounterIsRebuilt() {
        long templateId = createC1Template(5, 1, 0, 0);
        long userId = USER.incrementAndGet();
        issueGate.resetGate(templateId, 5, Duration.ofMinutes(5));
        redis.delete(ulKey(templateId, userId));

        ReceiveAsyncResult accepted = receive(templateId, userId,
                "gate-counter-missing-" + UUID.randomUUID());

        assertThat(accepted.taskId()).isNotNull();
        assertThat(redis.opsForValue().get(ulKey(templateId, userId))).isEqualTo("1");
    }

    @Test
    @DisplayName("Redis 错误：gate 偏高不超发，worker 由 DB 终审拒绝")
    void highGateDoesNotOversell() {
        long templateId = createC1Template(1, 1, 0, 0);
        issueGate.resetGate(templateId, 10, Duration.ofMinutes(5));
        long firstUser = USER.incrementAndGet();
        long secondUser = USER.incrementAndGet();
        String firstReq = "gate-high-1-" + UUID.randomUUID();
        String secondReq = "gate-high-2-" + UUID.randomUUID();

        ReceiveAsyncResult first = receive(templateId, firstUser, firstReq);
        ReceiveAsyncResult second = receive(templateId, secondUser, secondReq);
        drainOwnTasks(List.of(first.taskId(), second.taskId()));

        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(1);
        long rejected = java.util.stream.Stream.of(first.taskId(), second.taskId())
                .map(taskMapper::selectById)
                .filter(t -> t.getStatus() == CouponIssueTaskPO.STATUS_REJECTED)
                .count();
        assertThat(rejected).isEqualTo(1);
    }

    private ReceiveAsyncResult receive(long templateId, long userId, String requestId) {
        return receiveAsyncAppService.receive(new ReceiveRequest(requestId, userId, templateId));
    }

    private void forceGateDue(long taskId) {
        jdbc.update("UPDATE coupon_issue_task SET gate_next_retry_time = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0)), taskId);
    }

    private static String idemKey(long templateId, String requestId) {
        return "c:issue:idem:{" + templateId + "}:" + APP_ID + ":" + requestId;
    }

    private static String gateKey(long templateId) {
        return "c:issue:gate:{" + templateId + "}";
    }

    private static String ulKey(long templateId, long userId) {
        return "c:issue:ul:{" + templateId + "}:" + userId;
    }

    @Test
    @DisplayName("清理：只删终态旧任务，PENDING 永不删")
    void cleanupDeletesOnlyTerminalTasks() {
        long templateId = createC1Template(10, 1, 0, 0);
        long userId = USER.incrementAndGet();
        CouponIssueTaskPO task = manualTask(templateId, userId, "worker-cleanup");
        java.sql.Timestamp old = java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(8));

        jdbc.update("UPDATE coupon_issue_task SET create_time = ? WHERE id = ?", old, task.getId());
        cleanupJob.runOnce();
        assertThat(taskMapper.selectById(task.getId())).as("PENDING 不删").isNotNull();

        jdbc.update("UPDATE coupon_issue_task SET status = 2, create_time = ? WHERE id = ?",
                old, task.getId());
        cleanupJob.runOnce();
        assertThat(taskMapper.selectById(task.getId())).as("终态旧任务删除").isNull();
    }
}
