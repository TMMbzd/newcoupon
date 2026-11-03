package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.CouponCodeBatchCreateRequest;
import com.newcoupon.application.dto.CouponCodeBatchCreateResult;
import com.newcoupon.application.dto.CouponCodeRedeemRequest;
import com.newcoupon.application.dto.GrantTaskCreateRequest;
import com.newcoupon.application.dto.GrantTaskCreateResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.CouponCodeService;
import com.newcoupon.application.service.CouponIssueTaskWorker;
import com.newcoupon.application.service.GrantTaskService;
import com.newcoupon.application.service.GrantTaskWorker;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.ReceiveAsyncAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponGrantTaskPO;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 发券事件业务上下文落库（V6 阶段 1D 验收 #7，V43 五列）行为契约：
 * 同步/异步/批量/兑换四条链路发出的 ISSUED 事件，app_id/activity_id 取模板快照、
 * source 标链路、biz_type/biz_id 挂单据背景；无单据背景的领取三列落 NULL。
 *
 * <p>断言直接 JDBC 查 coupon_event（该表未分片——V6 决策：模板维度查询为主，
 * M4 配 ES 宽表；ShardingDataSourceConfig 只路由 user_coupon/coupon_usage/
 * coupon_expire_task/coupon_release_task 四族），无需按 user_id 定位物理分表。
 *
 * <p>四链路的 source 填值点：SYNC=ReceiveAppService 入口、ASYNC=CouponIssueTaskTxService
 * （单任务）与 CouponIssueTaskRawBulkWriter（裸写批量，本类 raw-bulk 默认开着，
 * 单用户无日限的任务满足批量条件，走的是裸写路径）、GRANT=GrantTaskWorker.executeItem、
 * CODE=CouponCodeService.redeem。
 *
 * <p>worker 调度全部拉长到 1 小时，用例手动 runOnce() 驱动（沿用
 * BudgetIntegrationTest / AsyncIssueWorkerIntegrationTest 的手法）。
 */
@Timeout(300)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.stock.shard-count=8",
                // 批量发放 worker（GRANT 链路）
                "coupon.grant.worker.enabled=true",
                "coupon.grant.worker.initial-delay-ms=3600000",
                "coupon.grant.worker.fixed-delay-ms=3600000",
                "coupon.grant.worker.retry-backoff-base-ms=200",
                // 异步发券受理 + worker（ASYNC 链路）
                "coupon.issue.async-enabled=true",
                "coupon.issue.async-template-ids=*",
                "coupon.issue.worker.enabled=true",
                "coupon.issue.worker.initial-delay-ms=3600000",
                "coupon.issue.worker.fixed-delay-ms=3600000",
                "coupon.circuit-breaker.min-calls=10000",
                "coupon.circuit-breaker.window-size=10000"
        })
class EventBizFieldsIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /**
     * 用户号段（全量套件共享库）：940/950（GrantTask/CouponCode）、960（Budget）、
     * 700（AsyncIssueWorker）已被占，本类用 970_000。
     */
    private static final AtomicLong USER = new AtomicLong(970_001);

    /** 建券用的活动号：activity 校验默认关，任意存在的号即可——断言只要求事件行原样快照它。 */
    private static final long ACTIVITY_ID = 770_001L;

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private ReceiveAsyncAppService receiveAsyncAppService;
    @Autowired
    private GrantTaskService grantTaskService;
    @Autowired
    private GrantTaskWorker grantWorker;
    @Autowired
    private CouponIssueTaskWorker issueWorker;
    @Autowired
    private CouponCodeService codeService;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private CouponGrantTaskMapper grantTaskMapper;
    @Autowired
    private CouponIssueTaskMapper issueTaskMapper;
    @Autowired
    private JdbcTemplate jdbcTpl;

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    /** 建券并发布：满减 1000 分、门槛 10000 分、库存 total、每人限领 1、无限日限（无限日限=异步任务满足裸写批量条件）。 */
    private long createTemplate(long total) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "事件归因验证券", ACTIVITY_ID, 1, 1000L, null, 10000L, total, 1,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, 0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }

    /**
     * 该用户最近一条发放事件（event_type=1）的归因五列。
     * 每个用例的用户都是新号，同号只发一张，LIMIT 1 即唯一。
     */
    private Map<String, Object> issuedEventOf(long userId) {
        return jdbcTpl.queryForMap(
                "SELECT app_id, activity_id, source, biz_type, biz_id FROM coupon_event "
                        + "WHERE user_id = ? AND event_type = 1 ORDER BY id DESC LIMIT 1", userId);
    }

    /** 驱动批量发放 worker 到任务终态（照 BudgetIntegrationTest 的模式）。 */
    private CouponGrantTaskPO drainGrantUntilTerminal(String taskNo, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        CouponGrantTaskPO task = grantTaskMapper.selectByTaskNo(taskNo);
        int status = task.getStatus();
        while (status != 2 && status != 3 && status != 5 && System.currentTimeMillis() < deadline) {
            grantWorker.runOnce();
            Thread.sleep(50);
            task = grantTaskMapper.selectByTaskNo(taskNo);
            status = task.getStatus();
        }
        assertThat(status).as("批量任务应在时限内到终态，实际 status=" + status).isIn(2, 3, 5);
        return grantTaskMapper.selectByTaskNo(taskNo);
    }

    // ------------------------------------------------------------------
    // ① 同步领取：source=SYNC、app_id/activity_id=模板行值、biz 三列 NULL
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("同步领取：事件 source=SYNC，app_id/activity_id=模板快照，biz_type/biz_id=NULL")
    void syncReceive_eventCarriesSyncSourceAndTemplateSnapshot() {
        long templateId = createTemplate(10);
        long user = USER.incrementAndGet();
        String templateAppId = templateRepository.findById(templateId).orElseThrow().getAppId();

        receiveAppService.receive(new ReceiveRequest("biz-sync-" + user, user, templateId));

        Map<String, Object> event = issuedEventOf(user);
        assertThat(event.get("source")).isEqualTo("SYNC");
        // app_id/activity_id 是发放时从模板行冗余的快照：与模板当前值一致
        assertThat(event.get("app_id")).isEqualTo(templateAppId);
        assertThat(event.get("activity_id")).isEqualTo(ACTIVITY_ID);
        // 同步领取没有单据背景：biz 三列必须落 NULL
        assertThat(event.get("biz_type")).isNull();
        assertThat(event.get("biz_id")).isNull();
    }

    // ------------------------------------------------------------------
    // ② 批量任务：source=GRANT、biz_type=GRANT_TASK、biz_id=taskNo
    // ------------------------------------------------------------------

    @Test
    @Timeout(180)
    @DisplayName("批量任务发放：事件 source=GRANT、biz_type=GRANT_TASK、biz_id=taskNo（运营可检索）")
    void grantTask_eventCarriesGrantSourceAndTaskNo() throws Exception {
        long templateId = createTemplate(10);
        long user = USER.incrementAndGet();

        GrantTaskCreateResult created = grantTaskService.createTask(new GrantTaskCreateRequest(
                "biz-grant-" + user, templateId, List.of(user), "tester"));
        CouponGrantTaskPO done = drainGrantUntilTerminal(created.taskNo(), 120_000);
        assertThat(done.getSuccessCount()).as("单用户任务应成功发出 1 张").isEqualTo(1);

        Map<String, Object> event = issuedEventOf(user);
        assertThat(event.get("source")).isEqualTo("GRANT");
        assertThat(event.get("biz_type")).isEqualTo("GRANT_TASK");
        assertThat(event.get("biz_id")).isEqualTo(created.taskNo());
        assertThat(event.get("app_id"))
                .isEqualTo(templateRepository.findById(templateId).orElseThrow().getAppId());
    }

    // ------------------------------------------------------------------
    // ③ 兑换码：source=CODE、biz_type=CODE、biz_id=batchNo
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("兑换码兑换：事件 source=CODE、biz_type=CODE、biz_id=batchNo（批次可读）")
    void codeRedeem_eventCarriesCodeSourceAndBatchNo() {
        long templateId = createTemplate(10);
        CouponCodeBatchCreateResult batch = codeService.createBatch(
                new CouponCodeBatchCreateRequest("biz-code-batch-" + templateId,
                        templateId, 1, 1, "tester"));
        long user = USER.incrementAndGet();

        codeService.redeem(new CouponCodeRedeemRequest(
                "biz-code-req-" + user, batch.codes().get(0), user));

        Map<String, Object> event = issuedEventOf(user);
        assertThat(event.get("source")).isEqualTo("CODE");
        assertThat(event.get("biz_type")).isEqualTo("CODE");
        assertThat(event.get("biz_id")).isEqualTo(batch.batchNo());
        assertThat(event.get("activity_id")).isEqualTo(ACTIVITY_ID);
    }

    // ------------------------------------------------------------------
    // ④ 异步受理任务（裸写批量路径）：source=ASYNC、biz 列 NULL
    //     ——任务规格只列了三例；ASYNC 填值点（含 RawBulkWriter 的裸 SQL 列清单）
    //     改动不小，加一例钉住，规格偏差见收尾报告。
    // ------------------------------------------------------------------

    @Test
    @Timeout(120)
    @DisplayName("异步任务发放：事件 source=ASYNC（裸写批量路径同样带归因），biz 列 NULL")
    void asyncIssue_eventCarriesAsyncSource() throws Exception {
        long templateId = createTemplate(10);
        long user = USER.incrementAndGet();

        var accepted = receiveAsyncAppService.receive(
                new ReceiveRequest("biz-async-" + user, user, templateId));
        // 与 AsyncIssueWorkerIntegrationTest 同款收口：反复 runOnce 到终态
        CouponIssueTaskPO task = issueTaskMapper.selectById(accepted.taskId());
        for (int round = 0; round < 20
                && task.getStatus() != CouponIssueTaskPO.STATUS_SUCCESS
                && task.getStatus() != CouponIssueTaskPO.STATUS_REJECTED
                && task.getStatus() != CouponIssueTaskPO.STATUS_DEAD; round++) {
            issueWorker.runOnce();
            Thread.sleep(50);
            task = issueTaskMapper.selectById(accepted.taskId());
        }
        assertThat(task.getStatus()).as("异步任务应在时限内 SUCCESS").isEqualTo(CouponIssueTaskPO.STATUS_SUCCESS);

        Map<String, Object> event = issuedEventOf(user);
        assertThat(event.get("source")).isEqualTo("ASYNC");
        assertThat(event.get("biz_type")).isNull();
        assertThat(event.get("biz_id")).isNull();
        assertThat(event.get("activity_id")).isEqualTo(ACTIVITY_ID);
    }
}
