package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.AuditService;
import com.newcoupon.infrastructure.persistence.mapper.AuditLogMapper;
import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 操作审计底座·验收测试（第 1 环 1-1 项）。
 *
 * <p>直接调 AuditService.record() 测试审计基础设施（六要素/哈希链/篡改检出），
 * 不通过业务方法——因为试算是只读操作（2026-10-11 用户评审纠正：高频只读操作不挂审计，
 * 审计只给"动钱"的写操作用）。第一个真实审计点将是第 2 环的核销（confirm）。
 *
 * <p>三个验收点：
 * <ol>
 *   <li><b>六要素齐全</b>：record() 后审计表出现记录，六要素全有值。</li>
 *   <li><b>哈希链有效</b>：连续多条记录的 prev_hash 逐条衔接。</li>
 *   <li><b>篡改可检出</b>：直接修改数据库里一条记录的内容，重算哈希与存储值不匹配。</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditLogIntegrationTest extends ShardedIntegrationTestSupport {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    /** 等待异步审计写入完成（轮询，最多 5 秒） */
    private List<AuditLogPO> waitForAuditRecords(int minCount) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            List<AuditLogPO> records = auditLogMapper.selectList(null);
            if (records != null && records.size() >= minCount) {
                return records;
            }
            Thread.sleep(100);
        }
        return auditLogMapper.selectList(null);
    }

    /** 产生一条审计记录（直接调 AuditService，不经过业务方法） */
    private void produceAuditRecord(String marker) {
        auditService.record(new AuditService.AuditContext(
                "test-app",                 // 谁：appId
                100L,                       // 谁：userId
                "trace-" + marker,          // 链路 ID
                "TEST_" + marker,           // 何事：action
                "TEST_RESOURCE",            // 何事：resourceType
                "resource-" + marker,       // 何事：resourceId
                "{\"before\":\"" + marker + "\"}",  // 前值快照
                "{\"after\":\"done\"}",     // 后值快照
                "127.0.0.1",                // 何处：IP
                0));                        // 结果码
    }

    @Test
    @Order(1)
    @DisplayName("T1: record() 后审计表出现记录，六要素齐全")
    void t1_auditRecordCreatedWithSixElements() throws Exception {
        produceAuditRecord("T1");

        List<AuditLogPO> records = waitForAuditRecords(1);
        assertThat(records.size()).as("审计表至少有一条记录").isGreaterThanOrEqualTo(1);

        AuditLogPO latest = records.get(records.size() - 1);
        assertThat(latest.getAppId()).as("要素①谁：appId").isEqualTo("test-app");
        assertThat(latest.getUserId()).as("要素①谁：userId").isEqualTo(100L);
        assertThat(latest.getTraceId()).as("要素⑥链路 ID").startsWith("trace-T1");
        assertThat(latest.getAction()).as("要素④何事：操作类型").isEqualTo("TEST_T1");
        assertThat(latest.getResourceType()).as("要素④何事：资源类型").isEqualTo("TEST_RESOURCE");
        assertThat(latest.getCreatedAt()).as("要素②何时").isNotNull();
        assertThat(latest.getBeforeSnapshot()).as("要素⑤前值快照").contains("T1");
        assertThat(latest.getAfterSnapshot()).as("要素⑤后值快照").isNotNull();
        assertThat(latest.getIp()).as("要素③何处：IP").isEqualTo("127.0.0.1");
        assertThat(latest.getRecordHash()).as("哈希链：本条哈希").isNotBlank();
        assertThat(latest.getPrevHash()).as("哈希链：前条哈希").isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("T2: 连续多条审计记录 → prev_hash 逐条衔接")
    void t2_hashChainContinuous() throws Exception {
        produceAuditRecord("T2a");
        produceAuditRecord("T2b");

        List<AuditLogPO> records = waitForAuditRecords(2);
        assertThat(records.size()).as("至少有两条记录").isGreaterThanOrEqualTo(2);

        records.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (int i = 1; i < records.size(); i++) {
            assertThat(records.get(i).getPrevHash())
                    .as("第 %d 条的 prev_hash 应等于第 %d 条的 record_hash",
                            records.get(i).getId(), records.get(i - 1).getId())
                    .isEqualTo(records.get(i - 1).getRecordHash());
        }
    }

    @Test
    @Order(3)
    @DisplayName("T3: 篡改一条审计记录的内容 → 重算哈希与存储值不匹配（篡改可检出）")
    void t3_tamperingDetected() throws Exception {
        produceAuditRecord("T3");
        List<AuditLogPO> records = waitForAuditRecords(1);
        assertThat(records.size()).isGreaterThanOrEqualTo(1);

        AuditLogPO victim = records.get(records.size() - 1);

        // 直接改库（模拟攻击者改 action 字段）
        auditLogMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AuditLogPO>()
                        .eq(AuditLogPO::getId, victim.getId())
                        .set(AuditLogPO::getAction, "TAMPERED"));

        AuditLogPO tampered = auditLogMapper.selectById(victim.getId());
        assertThat(tampered.getAction()).as("确认篡改生效").isEqualTo("TAMPERED");

        // 重算哈希：用被篡改的内容 + 原始 prev_hash 重新计算，应与存储的 record_hash 不匹配
        String recomputed = AuditService.computeHash(tampered);
        assertThat(recomputed)
                .as("篡改后重算的哈希应与存储值不匹配（篡改可检出）")
                .isNotEqualTo(tampered.getRecordHash());
    }
}
