package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.CouponIssueTaskBatchTxService;
import com.newcoupon.application.service.CouponIssueTaskRawBulkWriter;
import com.newcoupon.application.service.CouponIssueTaskService;
import com.newcoupon.application.service.CouponIssueTaskWorker;
import com.zaxxer.hikari.HikariDataSource;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * `coupon.issue.worker.raw-bulk-enabled=false` 的回退回归。
 *
 * <p>裸 JDBC 批量写只适配“1 物理库 × 64 表”。拆物理库或需要让所有写都经过
 * ShardingSphere 时，运维把开关关掉；本用例证明关掉后 worker 仍能完整发券，
 * 且不会调用裸写通道。
 *
 * <p>这里用真实 MySQL/Redis 容器，和 {@link AsyncIssueWorkerIntegrationTest}
 * 使用同一套测试基座；单独的 Spring 上下文是为了让 `@Value` 在启动时读到 false。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.stock.shard-count=8",
        "coupon.issue.async-enabled=true",
        "coupon.issue.async-template-ids=*",
        "coupon.issue.worker.enabled=true",
        "coupon.issue.worker.raw-bulk-enabled=false",
        "coupon.issue.worker.initial-delay-ms=3600000",
        "coupon.issue.worker.fixed-delay-ms=3600000",
        "coupon.issue.worker.batch-size=10",
        "coupon.issue.worker.batch-tx-size=10",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class AsyncIssueWorkerRawBulkDisabledIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger USER = new AtomicInteger(900_000);
    private static final String APP_ID = "worker-raw-off-" + Long.toHexString(System.nanoTime());
    private final List<Long> templates = new ArrayList<>();

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private CouponIssueTaskService taskService;
    @Autowired
    private CouponIssueTaskMapper taskMapper;
    @Autowired
    private CouponIssueTaskWorker worker;
    @Autowired
    private UserCouponRepository userCouponRepository;
    @Autowired
    private CouponStockShardMapper shardMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConfigurableApplicationContext context;

    /** 真实 Bean 外面包一层 spy，只用于断言“开关关掉后没有走裸写”。 */
    @SpyBean
    private CouponIssueTaskRawBulkWriter rawBulkWriter;
    /** 记录整批入口调用：证明 worker 确实进入了读开关的 processAll，而不是绕到逐条。 */
    @SpyBean
    private CouponIssueTaskBatchTxService batchTxService;

    @BeforeEach
    void bindAppContext() {
        RequestContext.set(new RequestContext(APP_ID, "worker-raw-off-test", Long.MAX_VALUE, false));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM coupon_issue_task WHERE app_id = ?", APP_ID);
        for (long templateId : templates) {
            jdbc.update("DELETE FROM coupon_user_daily_counter WHERE template_id = ?", templateId);
            jdbc.update("DELETE FROM coupon_daily_counter WHERE template_id = ?", templateId);
            // 保留 coupon_stock_shard：模板行和 user_coupon 行仍在，这三张表必须一起自洽；
            // 只删库存行会让其它测试的 C1 全量审计误报漂移。
        }
        RequestContext.clear();
    }

    private long createC1Template(long total, int perUserLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "raw-off验证券", 3002L, 1, 1000L, null, 10000L, total, perUserLimit,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                0L, 0));
        templateAppService.publish(created.id());
        templates.add(created.id());
        return created.id();
    }

    private CouponIssueTaskPO manualTask(long templateId, long userId, String requestId) {
        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();
        return taskService.accept(new ReceiveRequest(requestId, userId, templateId),
                template, LocalDate.now(), 1);
    }

    /**
     * 把任务的 next_scan_time 置到一个确定的过去时间。
     *
     * <p>2026-10-23 全量测试实测：WSL 在用例创建任务期间发生约 18 秒时钟回拨，
     * 前几条任务的 next_scan_time 落在回拨后的“未来”，worker 的 now 比它们小，
     * 只领到了后两条。生产代码用 JVM 当前时间做扫描时间，这个用例不替它改语义，
     * 但测试必须显式构造“可领取”前置状态，否则会被环境时钟回拨误伤。
     */
    private void makeClaimable(List<Long> taskIds) {
        Timestamp past = Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0));
        for (Long taskId : taskIds) {
            jdbc.update("UPDATE coupon_issue_task SET next_scan_time = ? WHERE id = ?", past, taskId);
        }
    }

    /**
     * 反复领取直到本用例的任务都到终态。worker 每轮最多领 10 条，全量测试里
     * 其它用例可能留下待领取任务；只跑一轮会把“没轮到我”误判成 worker 失败。
     */
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

    @Test
    @DisplayName("raw-bulk-enabled=false：所有任务走单任务事务，券/库存/事件/outbox 仍完整")
    void disabledSwitchFallsBackToSingleTaskTransactions() {
        long templateId = createC1Template(10, 1);
        List<Long> userIds = new ArrayList<>();
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long userId = USER.incrementAndGet();
            userIds.add(userId);
            taskIds.add(manualTask(templateId, userId, "raw-off-" + UUID.randomUUID()).getId());
        }

        makeClaimable(taskIds);
        drainOwnTasks(taskIds);

        for (Long taskId : taskIds) {
            CouponIssueTaskPO task = taskMapper.selectById(taskId);
            assertThat(task.getStatus()).as("task=%s", taskId)
                    .isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);
            assertThat(task.getCouponId()).isNotNull();
        }
        assertThat(shardMapper.sumUsed(templateId)).isEqualTo(5);
        assertThat(userCouponRepository.countByTemplate(templateId)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon_event WHERE template_id = ?", Long.class, templateId))
                .as("每张券一条 ISSUED 事件").isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon_outbox o JOIN coupon_event e ON o.event_no = e.event_no "
                        + "WHERE e.template_id = ?", Long.class, templateId))
                .as("每个事件一条 outbox").isEqualTo(5L);
        for (Long userId : userIds) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM coupon_expire_task WHERE user_id = ?", Long.class, userId))
                    .as("user=%s 的过期任务", userId).isEqualTo(1L);
        }

        verify(batchTxService, atLeastOnce()).processAll(
                argThat(items -> items.stream().anyMatch(item ->
                        item.task().getTemplateId().equals(templateId))), any());
        Object rawPool = context.getBeanFactory().getSingleton("rawBulkDataSource");
        if (rawPool instanceof HikariDataSource hikari) {
            assertThat(hikari.isRunning())
                    .as("开关关闭时 raw-bulk 连接池不应启动")
                    .isFalse();
        }
        verify(rawBulkWriter, never()).write(any(), any());
    }
}
