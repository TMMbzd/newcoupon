package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.GateCounterReconcileJob;
import com.newcoupon.application.service.GateCounterRepairService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 计数对账的端到端合同（讨论 02 核对结论 P4）：
 * 人为把 ul/gate/uld 写坏 → 跑一轮对账 → Redis 恢复为 DB 目标、漂移日志留痕；
 * 无偏差时第二轮不产生新的漂移记录。
 */
@SpringBootTest(properties = {
        "coupon.stock.shard-count=4",
        "coupon.issue.async-enabled=true",
        "coupon.issue.async-template-ids=*",
        "coupon.issue.worker.enabled=true",
        "coupon.issue.worker.initial-delay-ms=3600000",
        "coupon.issue.worker.fixed-delay-ms=3600000",
        "coupon.issue.gate-compensation.initial-delay-ms=3600000",
        "coupon.issue.gate-compensation.fixed-delay-ms=3600000",
        "coupon.issue.gate-repair.initial-delay-ms=3600000",
        "coupon.issue.gate-repair.fixed-delay-ms=3600000",
        "coupon.issue.gate-reconcile.initial-delay-ms=3600000",
        "coupon.issue.gate-reconcile.fixed-delay-ms=3600000",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class GateCounterReconcileIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    private static final long USER = 930_001L;
    private static final long ACTIVITY = 910_000_000L;

    @Autowired
    private GateCounterReconcileJob reconcileJob;
    @Autowired
    private GateCounterRepairService counterRepairService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Qualifier("rawBulkDataSource")
    @Autowired
    private javax.sql.DataSource rawBulkDataSource;
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.newcoupon.application.service.CouponIssueTaskWorker worker;
    @Autowired
    private com.newcoupon.application.service.TemplateAppService templateAppService;
    @Autowired
    private com.newcoupon.application.service.GateRebuildService gateRebuildService;

    private final List<Long> templates = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : templates) {
            jdbc.update("DELETE FROM coupon_stock_shard WHERE template_id = ?", id);
            jdbc.update("DELETE FROM coupon_template WHERE id = ?", id);
            jdbc.update("DELETE FROM coupon_issue_task WHERE template_id = ?", id);
            jdbc.update("DELETE FROM coupon_user_daily_counter WHERE template_id = ?", id);
            jdbc.update("DELETE FROM coupon_daily_counter WHERE template_id = ?", id);
            redis.delete("c:issue:gate:{" + id + "}");
            redis.delete("c:issue:ul:{" + id + "}:" + USER);
            redis.delete("c:issue:uld:{" + id + "}:" + USER + ":" + LocalDate.now());
            redis.delete("c:issue:dly:{" + id + "}:" + LocalDate.now());
        }
        JdbcTemplate raw = new JdbcTemplate(rawBulkDataSource);
        raw.update("DELETE FROM user_coupon_" + Math.floorMod(USER, 64)
                + " WHERE user_id = ?", USER);
    }

    @Test
    @DisplayName("坏 ul/gate/uld：对账一轮恢复为 DB 目标并留漂移；第二轮无新增漂移")
    void tamperedCountersAreReconciled() {
        // 建模板并预置 DB 事实：用户已发 1 张券（直接插券表），gate = Σquota-Σused
        long templateId = createTemplate(10, 3, 0, 0);
        JdbcTemplate raw = new JdbcTemplate(rawBulkDataSource);
        raw.update("INSERT INTO user_coupon_" + Math.floorMod(USER, 64)
                + " (id, user_id, template_id, template_code, face_value, threshold, discount_cap, "
                + "status, receive_time, valid_end, idempotent_key, seq, app_id) "
                + "VALUES (?, ?, ?, 'RECON', 100, 0, 100, 0, NOW(3), NOW(3) + INTERVAL 7 DAY, ?, 1, '1')",
                ACTIVITY + 1, USER, templateId, "recon-" + UUID.randomUUID());
        jdbc.update("UPDATE coupon_stock_shard SET used = 1 WHERE template_id = ? AND shard_no = 0",
                templateId);
        long expectedGate = gateRebuildService.rebuild(templateId, true);

        // 写坏三个键：ul 偏低 1、gate 偏低 2、uld 偏高 99
        String ulKey = "c:issue:ul:{" + templateId + "}:" + USER;
        String gateKey = "c:issue:gate:{" + templateId + "}";
        String uldKey = "c:issue:uld:{" + templateId + "}:" + USER + ":" + LocalDate.now();
        redis.opsForValue().set(ulKey, "0");
        redis.opsForValue().set(gateKey, String.valueOf(Math.max(0, expectedGate - 2)));
        redis.opsForValue().set(uldKey, "99");

        long driftBefore = countDrift(templateId);
        reconcileJob.runOnce();

        long ulTarget = counterRepairService.computeUserTotalTarget(USER, templateId);
        assertThat(Long.valueOf(redis.opsForValue().get(ulKey))).isEqualTo(ulTarget);
        assertThat(Long.valueOf(redis.opsForValue().get(gateKey)))
                .isEqualTo(gateRebuildService.rebuild(templateId, true));
        // uld 目标 = DB 计数 + 在途 = 0：对账会把它从 99 修回 0
        assertThat(Long.valueOf(redis.opsForValue().get(uldKey)))
                .isEqualTo(counterRepairService.computeUserDailyTarget(USER, templateId, LocalDate.now()));
        assertThat(countDrift(templateId)).isEqualTo(driftBefore + 3);

        // 第二轮：无偏差，不再新增漂移
        reconcileJob.runOnce();
        assertThat(countDrift(templateId)).isEqualTo(driftBefore + 3);
    }

    private long countDrift(long templateId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_drift_log WHERE template_id = ? AND action LIKE '%reconcile%'",
                Long.class, templateId);
        return n == null ? 0 : n;
    }

    private long createTemplate(long total, int perUserLimit,
                                long dailyLimit, int perUserDailyLimit) {
        com.newcoupon.application.service.TemplateAppService.CreatedTemplate created =
                templateAppService.create(new com.newcoupon.application.dto.TemplateCreateRequest(
                        "对账验证券", 3001L, 1, 1000L, null, 10000L, total, perUserLimit,
                        1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                        dailyLimit, perUserDailyLimit));
        templateAppService.publish(created.id());
        templates.add(created.id());
        return created.id();
    }
}
