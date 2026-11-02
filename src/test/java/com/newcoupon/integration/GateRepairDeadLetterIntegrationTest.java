package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.CouponIssueGateRepairJob;
import com.newcoupon.application.service.GateRepairQueueService;
import com.newcoupon.application.service.GateRebuildService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponIssueGateRepairMapper;
import com.newcoupon.infrastructure.persistence.po.CouponIssueGateRepairPO;
import com.newcoupon.infrastructure.redis.RedisIssueGate;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V36 修复队列的死信契约：attempts 达到 max-attempts 时行转死信（status=3），
 * 并在 stock_drift_log 落一条 REDIS_GATE_REPAIR_DEAD（人工/对账的入口）。
 *
 * <p>失败注入用 @SpyBean 只把 {@link GateRebuildService#rebuild} 打成抛异常：
 * 修复链路的 Redis 写全是"覆盖式 SET"（类型错了也不抛）、读全有 pcall 兜底，
 * 用数据形态造不出确定性失败——DB 拥塞更不可控，mock 这一处是唯一稳定的选择。
 * max-attempts=1：首次失败即 next(1) >= maxAttempts(1)，走 markDead 而不是 markRetry。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.stock.shard-count=8",
        "coupon.issue.gate-repair.max-attempts=1",
        "coupon.issue.gate-repair.fixed-delay-ms=3600000",
        "coupon.issue.gate-repair.initial-delay-ms=3600000",
        "coupon.circuit-breaker.min-calls=10000",
        "coupon.circuit-breaker.window-size=10000"
})
class GateRepairDeadLetterIntegrationTest extends ShardedIntegrationTestSupport {

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
    private TemplateRepository templateRepository;
    @Autowired
    private GateRepairQueueService queueService;
    @Autowired
    private CouponIssueGateRepairJob repairJob;
    @Autowired
    private CouponIssueGateRepairMapper repairMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    @SpyBean
    private GateRebuildService gateRebuildService;

    private long templateId;

    @AfterEach
    void cleanup() {
        // 死信行与漂移日志都是"留痕"数据，但测试的行不能留给对账类用例当背景噪音
        jdbc.update("DELETE FROM coupon_issue_gate_repair WHERE template_id = ?", templateId);
        jdbc.update("DELETE FROM stock_drift_log WHERE template_id = ? AND action = 'REDIS_GATE_REPAIR_DEAD'",
                templateId);
        if (templateId > 0) {
            redis.delete("c:issue:gate:{" + templateId + "}");
        }
    }

    @Test
    @DisplayName("attempts 耗尽：修复持续失败 → 行转死信(status=3) + stock_drift_log 落 REDIS_GATE_REPAIR_DEAD")
    void repairFailureExhaustsAttemptsIntoDeadLetter() {
        templateId = createTemplate();
        redis.delete("c:issue:gate:{" + templateId + "}");
        // GATE_MISSING 是模板级作用域：登记行的 user_id 固定 0（cmd 里的 userId 不落行）
        CouponIssueGateRepairPO row = enqueue(RedisIssueGate.GATE_MISSING, 0);
        makeDue(row.getId());

        // 注入确定性失败：重建动作本身抛错（其余依赖全是真组件）
        doThrow(new IllegalStateException("injected rebuild failure"))
                .when(gateRebuildService).rebuild(anyLong(), anyBoolean());

        repairJob.runOnce();

        CouponIssueGateRepairPO dead = repairMapper.selectById(row.getId());
        assertThat(dead.getStatus()).as("attempts=0 → next=1 已达 max-attempts=1，必须转死信")
                .isEqualTo(CouponIssueGateRepairPO.STATUS_DEAD);
        assertThat(dead.getAttempts()).isEqualTo(1);
        assertThat(dead.getLastError()).as("最后一次失败原因要留痕").contains("injected");

        // 死信不是无痕丢弃：漂移日志是人工处理/对账的唯一入口
        Integer driftCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_drift_log WHERE template_id = ? "
                        + "AND action = 'REDIS_GATE_REPAIR_DEAD'", Integer.class, templateId);
        assertThat(driftCount).as("死信必须落 stock_drift_log（REDIS_GATE_REPAIR_DEAD）").isEqualTo(1);
        String detail = jdbc.queryForObject(
                "SELECT detail FROM stock_drift_log WHERE template_id = ? "
                        + "AND action = 'REDIS_GATE_REPAIR_DEAD' LIMIT 1", String.class, templateId);
        assertThat(detail).as("明细带上下文：模板/错误码/attempts，能直接开工排查")
                .contains("templateId=" + templateId)
                .contains("errorCode=" + RedisIssueGate.GATE_MISSING);
    }

    /** 与 GateRepairQueueIntegrationTest 同款建券：满 1000 减 1000、总 5 张、每人 1 张、无限额 */
    private long createTemplate() {
        TemplateAppService.CreatedTemplate created = templateAppService.create(new TemplateCreateRequest(
                "修复死信验证券", 1001L, 1, 1000L, null, 10000L, 5L, 1,
                1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                0L, 0));
        templateAppService.publish(created.id());
        return created.id();
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
}
