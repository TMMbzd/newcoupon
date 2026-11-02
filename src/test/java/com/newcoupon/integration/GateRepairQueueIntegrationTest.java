package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.CouponIssueGateRepairJob;
import com.newcoupon.application.service.GateRepairKeys;
import com.newcoupon.application.service.GateRepairQueueService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueGateRepairMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueGateRepairPO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V36 修复队列契约：后台任务能独立于请求路径修 gate/计数，单飞许可被占用时退避。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.stock.shard-count=8",
        "coupon.issue.gate-repair.fixed-delay-ms=3600000",
        "coupon.issue.gate-repair.initial-delay-ms=3600000",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class GateRepairQueueIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final AtomicInteger USER = new AtomicInteger(810_000);

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private GateRepairQueueService queueService;
    @Autowired
    private CouponIssueGateRepairJob repairJob;
    @Autowired
    private CouponIssueGateRepairMapper repairMapper;
    @Autowired
    private RedisIssueGate issueGate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    private long templateId;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM coupon_issue_gate_repair WHERE template_id = ?", templateId);
        if (templateId > 0) {
            redis.delete("c:issue:gate:{" + templateId + "}");
        }
    }

    @Test
    @DisplayName("后台任务重建缺失 gate：队列行完成，gate 恢复为 Σquota-Σused-在途")
    void backgroundRepairRebuildsMissingGate() {
        templateId = createTemplate(5, 1);
        redis.delete(gateKey(templateId));
        CouponIssueGateRepairPO row = enqueue(RedisIssueGate.GATE_MISSING, 0);
        makeDue(row.getId());

        repairJob.runOnce();

        CouponIssueGateRepairPO done = repairMapper.selectById(row.getId());
        assertThat(done.getStatus()).isEqualTo(CouponIssueGateRepairPO.STATUS_DONE);
        assertThat(issueGate.currentGate(templateId)).isEqualTo(5L);
    }

    @Test
    @DisplayName("单飞许可被占用：队列退避，不重复修数据库")
    void busyPermitLeavesRetry() {
        templateId = createTemplate(5, 1);
        redis.delete(gateKey(templateId));
        CouponIssueGateRepairPO row = enqueue(RedisIssueGate.GATE_MISSING, 0);
        makeDue(row.getId());
        String permitKey = GateRepairKeys.permitKey(templateId, 0, RedisIssueGate.GATE_MISSING);
        assertThat(issueGate.tryAcquireRepairPermit(permitKey, "other-instance",
                Duration.ofSeconds(30))).isTrue();
        try {
            repairJob.runOnce();

            CouponIssueGateRepairPO retry = repairMapper.selectById(row.getId());
            assertThat(retry.getStatus()).isEqualTo(CouponIssueGateRepairPO.STATUS_PENDING);
            assertThat(retry.getAttempts()).isEqualTo(1);
            assertThat(issueGate.currentGate(templateId)).isNull();
        } finally {
            issueGate.releaseRepairPermit(permitKey, "other-instance");
        }
    }

    @Test
    @DisplayName("后台任务修复某人偏高的限领计数，不影响其他用户")
    void backgroundRepairFixesUserCounter() {
        templateId = createTemplate(5, 1);
        long userId = USER.incrementAndGet();
        redis.opsForValue().set("c:issue:ul:{" + templateId + "}:" + userId, "99",
                Duration.ofMinutes(5));
        CouponIssueGateRepairPO row = enqueue(RedisIssueGate.USER_TOTAL_LIMIT, userId);
        makeDue(row.getId());

        repairJob.runOnce();

        assertThat(redis.opsForValue().get("c:issue:ul:{" + templateId + "}:" + userId))
                .isEqualTo("0");
        assertThat(repairMapper.selectById(row.getId()).getStatus())
                .isEqualTo(CouponIssueGateRepairPO.STATUS_DONE);
    }

    @Test
    @DisplayName("死信行再次登记会复活为待处理，attempts 归零")
    void enqueueResurrectsDeadRow() {
        templateId = createTemplate(5, 1);
        CouponIssueGateRepairPO row = enqueue(RedisIssueGate.GATE_MISSING, 0);
        jdbc.update("UPDATE coupon_issue_gate_repair SET status = 3, attempts = 8 WHERE id = ?",
                row.getId());

        enqueue(RedisIssueGate.GATE_MISSING, 0);

        CouponIssueGateRepairPO revived = repairMapper.selectById(row.getId());
        assertThat(revived.getStatus()).isEqualTo(CouponIssueGateRepairPO.STATUS_PENDING);
        assertThat(revived.getAttempts()).isZero();
    }

    private CouponIssueGateRepairPO enqueue(int errorCode, long userId) {
        CouponTemplate template = templateRepository.findById(templateId).orElseThrow();
        RedisIssueGate.GateCommand cmd = new RedisIssueGate.GateCommand(
                templateId, template.getAppId(), userId, "repair-" + UUID.randomUUID(),
                template.getPerUserLimit(), template.getPerUserDailyLimit(), template.getDailyLimit(),
                LocalDate.now(), 604800, 172800);
        queueService.enqueue(cmd, errorCode);
        return repairMapper.selectList(null).stream()
                .filter(r -> r.getTemplateId().equals(templateId))
                .filter(r -> r.getErrorCode().equals(errorCode))
                .filter(r -> r.getUserId().equals(userId))
                .findFirst()
                .orElseThrow();
    }

    private void makeDue(long id) {
        jdbc.update("UPDATE coupon_issue_gate_repair SET next_retry_time = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0)), id);
    }

    private long createTemplate(long total, int perUserLimit) {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "修复队列验证券", 1001L, 1, 1000L, null, 10000L, total, perUserLimit,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                0L, 0));
        templateAppService.publish(created.id());
        return created.id();
    }

    private static String gateKey(long templateId) {
        return "c:issue:gate:{" + templateId + "}";
    }
}
