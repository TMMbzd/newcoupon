package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.newcoupon.application.dto.ReceiveAsyncResult;
import com.newcoupon.application.dto.ReceiveAsyncStatusResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAsyncAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueTaskPO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ADR-0019 受理链路契约：Redis 准入 → 任务表 WAL → 202 语义 → 状态查询 → 失败补偿。
 *
 * <p>本类只验证“已受理”，不验证 worker 落券；worker 是下一阶段。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.stock.shard-count=8",
        "coupon.issue.async-enabled=true",
        "coupon.issue.async-template-ids=*",
        // 本类只验受理：worker 配置必须同时打开（启动校验），但把调度延迟拉长，
        // 避免 @Scheduled 把 PENDING 任务提前处理掉。
        "coupon.issue.worker.enabled=true",
        "coupon.issue.worker.initial-delay-ms=3600000",
        "coupon.issue.worker.fixed-delay-ms=3600000",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class AsyncIssueAcceptanceIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger USER = new AtomicInteger(500_000);
    /** 每次 JVM/测试类唯一：避免共享 MySQL/Redis 时与其它轮次的任务唯一键/幂等键串数据。 */
    private static final String APP_ID = "async-" + Long.toHexString(System.nanoTime());

    @BeforeEach
    void bindAppContext() {
        RequestContext.set(new RequestContext(APP_ID, "async-test", Long.MAX_VALUE, false));
    }

    @AfterEach
    void clearAppContext() {
        // 测试共享 MySQL/Redis：清掉本类任务，避免后续 worker 测试领到残留 PENDING。
        taskMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CouponIssueTaskPO>()
                .eq("app_id", APP_ID));
        RequestContext.clear();
    }

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAsyncAppService receiveAsyncAppService;
    @Autowired
    private CouponIssueTaskMapper taskMapper;
    @Autowired
    private RedisIssueGate issueGate;
    @Autowired
    private StringRedisTemplate redis;

    private long createC1Template(long total, int perUserLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "异步受理验证券", 2001L, 1, 1000L, null, 10000L, total, perUserLimit,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                null, null));
        templateAppService.publish(created.id());
        return created.id();
    }

    @Test
    @DisplayName("受理：返回 ACCEPTED，任务表一行，stat_day=受理日；重放返回同一 taskId")
    void acceptWritesTaskAndIsIdempotent() {
        long templateId = createC1Template(10, 2);
        long userId = USER.incrementAndGet();
        String run = uuid();
        String requestId = "async-ok-" + run + "-" + userId;

        ReceiveAsyncResult first = receiveAsyncAppService.receive(
                new ReceiveRequest(requestId, userId, templateId));
        assertThat(first.status()).isEqualTo("ACCEPTED");
        assertThat(first.taskId()).isNotNull();

        CouponIssueTaskPO task = taskMapper.selectByUniqueKey(APP_ID, userId, requestId);
        assertThat(task).isNotNull();
        assertThat(task.getStatus()).isEqualTo(CouponIssueTaskPO.STATUS_PENDING);
        assertThat(task.getStatDay()).isEqualTo(LocalDate.now());
        assertThat(task.getAppId()).isEqualTo(APP_ID);

        ReceiveAsyncResult replay = receiveAsyncAppService.receive(
                new ReceiveRequest(requestId, userId, templateId));
        assertThat(replay.taskId()).isEqualTo(first.taskId());
        assertThat(replay.status()).isEqualTo("ACCEPTED");
        assertThat(taskMapper.countByStatus(CouponIssueTaskPO.STATUS_PENDING)).isGreaterThanOrEqualTo(1);

        ReceiveAsyncStatusResult status = receiveAsyncAppService.status(APP_ID, userId, requestId);
        assertThat(status.status()).isEqualTo("ACCEPTED");
        assertThat(status.couponId()).isNull();
        assertThat(status.errorCode()).isNull();
        assertThat(redis.opsForValue().get(idemKey(templateId, requestId))).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("gate 售罄：第二个用户被拒且不落任务")
    void gateSoldOutRejectsWithoutTask() {
        long templateId = createC1Template(1, 1);
        long firstUser = USER.incrementAndGet();
        long secondUser = USER.incrementAndGet();
        String run = uuid();

        receiveAsyncAppService.receive(new ReceiveRequest("async-sold-" + run + "-1", firstUser, templateId));

        BizException e = catchThrowableOfType(() -> receiveAsyncAppService.receive(
                new ReceiveRequest("async-sold-" + run + "-2", secondUser, templateId)), BizException.class);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_OUT);
        assertThat(taskMapper.selectByUniqueKey(APP_ID, secondUser, "async-sold-" + run + "-2")).isNull();
        assertThat(issueGate.currentGate(templateId)).isZero();
    }

    @Test
    @DisplayName("gate 丢失后从 C1 分片重建，剩余额度继续可用")
    void gateRebuildsFromC1Shards() {
        long templateId = createC1Template(2, 1);
        long firstUser = USER.incrementAndGet();
        long secondUser = USER.incrementAndGet();
        String run = uuid();

        receiveAsyncAppService.receive(new ReceiveRequest("async-rebuild-" + run + "-1", firstUser, templateId));
        redis.delete(gateKey(templateId));

        ReceiveAsyncResult second = receiveAsyncAppService.receive(
                new ReceiveRequest("async-rebuild-" + run + "-2", secondUser, templateId));
        assertThat(second.status()).isEqualTo("ACCEPTED");
        assertThat(taskMapper.selectByUniqueKey(APP_ID, secondUser,
                "async-rebuild-" + run + "-2")).isNotNull();
    }

    @Test
    @DisplayName("跨模板复用 requestId：任务唯一键收敛，第二个模板的预扣被补偿")
    void sameRequestIdOnAnotherTemplateIsRejectedAndCompensated() {
        long templateA = createC1Template(5, 1);
        long templateB = createC1Template(5, 1);
        long userId = USER.incrementAndGet();
        String requestId = "async-cross-" + uuid() + "-" + userId;

        receiveAsyncAppService.receive(new ReceiveRequest(requestId, userId, templateA));
        assertThat(issueGate.ensureGate(templateB, 5, Duration.ofMinutes(5))).isTrue();
        Long gateBefore = issueGate.currentGate(templateB);

        BizException e = catchThrowableOfType(() -> receiveAsyncAppService.receive(
                new ReceiveRequest(requestId, userId, templateB)), BizException.class);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(taskMapper.selectByUniqueKey(APP_ID, userId, requestId).getTemplateId())
                .isEqualTo(templateA);
        // templateB 的 gate 不能留下这次失败的预扣
        assertThat(issueGate.currentGate(templateB)).isEqualTo(gateBefore);
    }

    @Test
    @DisplayName("补偿脚本只回退 PENDING，不重复减计数")
    void compensateIsIdempotentAndOnlyForPending() {
        long templateId = createC1Template(5, 1);
        long userId = USER.incrementAndGet();
        LocalDate day = LocalDate.now();
        RedisIssueGate.GateCommand cmd = new RedisIssueGate.GateCommand(
                templateId, APP_ID, userId, "async-comp-" + uuid() + "-" + userId,
                1, 0, 0, day, 600, 120);
        assertThat(issueGate.ensureGate(templateId, 5, Duration.ofMinutes(5))).isTrue();
        // 新 gate 的计数/seq 先按 DB 目标初始化（真实入口由 GateCounterRepairService 完成）。
        issueGate.repairUserTotal(cmd, 0);
        issueGate.ensureSeqAtLeast(cmd, 0);

        RedisIssueGate.GateResult acquired = issueGate.tryAcquire(cmd);
        assertThat(acquired.success()).isTrue();
        assertThat(issueGate.currentGate(templateId)).isEqualTo(4);

        assertThat(issueGate.compensate(cmd)).isTrue();
        assertThat(issueGate.currentGate(templateId)).isEqualTo(5);
        assertThat(redis.hasKey(idemKey(templateId, cmd.requestId()))).isFalse();
        assertThat(issueGate.compensate(cmd)).isFalse();

        assertThat(issueGate.tryAcquire(cmd).success()).isTrue();
        issueGate.markAccepted(cmd);
        assertThat(issueGate.compensate(cmd)).isFalse();
    }

    @Test
    @DisplayName("未知 requestId 查询返回 3005")
    void unknownRequestIdReturnsTaskNotFound() {
        long userId = USER.incrementAndGet();
        BizException e = catchThrowableOfType(() -> receiveAsyncAppService.status(
                APP_ID, userId, "missing-task-" + uuid()), BizException.class);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ISSUE_TASK_NOT_FOUND);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String idemKey(long templateId, String requestId) {
        return "c:issue:idem:{" + templateId + "}:" + APP_ID + ":" + requestId;
    }

    private static String gateKey(long templateId) {
        return "c:issue:gate:{" + templateId + "}";
    }
}
