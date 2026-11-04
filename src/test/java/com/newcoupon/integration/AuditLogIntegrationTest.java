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

    /** T3 的篡改对象与原值（M4）：库跨类共享，篡改行不还原会把后面所有 verify 用例卡在假断点上。 */
    private Long tamperedRowId;
    private String tamperedOriginalAction;

    /**
     * 还原 T3 的篡改行（M4，实测复现：不还原时 AuditQueryIntegrationTest 的 verify
     * 用例断点定位全部错位、三类联跑必红）。还原=改回原 action + 按当前内容重算
     * record_hash——内容复原后重算值与原存储哈希本就一致，重算是防御性的：
     * 将来有更狠的篡改用例（连哈希一起改）时同一套还原仍然自洽。
     */
    @org.junit.jupiter.api.AfterEach
    void restoreTamperedRow() {
        if (tamperedRowId == null) {
            return;
        }
        auditLogMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AuditLogPO>()
                        .eq(AuditLogPO::getId, tamperedRowId)
                        .set(AuditLogPO::getAction, tamperedOriginalAction));
        AuditLogPO restored = auditLogMapper.selectById(tamperedRowId);
        if (restored != null) {
            auditLogMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AuditLogPO>()
                            .eq(AuditLogPO::getId, tamperedRowId)
                            .set(AuditLogPO::getRecordHash, AuditService.computeHash(restored)));
        }
        tamperedRowId = null;
        tamperedOriginalAction = null;
    }

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

    /**
     * 等待并只取指定 action 的记录（2026-10-15 修测试隔离）。
     * 背景：核销/退款挂上真实审计后，同容器其它集成测试会并发写审计行——
     * "取全表最后一条"会取到业务审计（SYSTEM 行），断言错对象。
     * 按 action 过滤后，断言对象恒为本测试自己写的记录。
     */
    private List<AuditLogPO> waitForAuditRecordsByAction(String action) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            List<AuditLogPO> records = auditLogMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditLogPO>()
                            .eq(AuditLogPO::getAction, action));
            if (records != null && !records.isEmpty()) {
                return records;
            }
            Thread.sleep(100);
        }
        return List.of();
    }

    /**
     * 产生一条审计记录（直接调 AuditService，不经过业务方法）。
     * V6 2C 起走带 actor 三列的工厂方法（hashVersion 由服务端钉死为 2）。
     */
    private void produceAuditRecord(String marker) {
        auditService.record(AuditService.AuditContext.of(
                "test-app",                 // 谁：appId
                100L,                       // 谁：userId（操作对象维度）
                "trace-" + marker,          // 链路 ID
                "TEST_" + marker,           // 何事：action
                "TEST_RESOURCE",            // 何事：resourceType
                "resource-" + marker,       // 何事：resourceId
                "{\"before\":\"" + marker + "\"}",  // 前值快照
                "{\"after\":\"done\"}",     // 后值快照
                "127.0.0.1",                // 何处：IP
                0,                          // 结果码
                "TENANT", "test-app", null));   // actor：模拟开放面应用发起
    }

    @Test
    @Order(1)
    @DisplayName("T1: record() 后审计表出现记录，六要素齐全")
    void t1_auditRecordCreatedWithSixElements() throws Exception {
        produceAuditRecord("T1");

        List<AuditLogPO> records = waitForAuditRecordsByAction("TEST_T1");
        assertThat(records).as("本测试写过一条 TEST_T1 记录").hasSize(1);

        AuditLogPO latest = records.get(0);
        assertThat(latest.getAppId()).as("要素①谁：appId").isEqualTo("test-app");
        assertThat(latest.getUserId()).as("要素①谁：userId").isEqualTo(100L);
        assertThat(latest.getTraceId()).as("要素⑥链路 ID").startsWith("trace-T1");
        assertThat(latest.getAction()).as("要素④何事：操作类型").isEqualTo("TEST_T1");
        assertThat(latest.getResourceType()).as("要素④何事：资源类型").isEqualTo("TEST_RESOURCE");
        assertThat(latest.getCreatedAt()).as("要素②何时").isNotNull();
        assertThat(latest.getBeforeSnapshot()).as("要素⑤前值快照").contains("T1");
        assertThat(latest.getAfterSnapshot()).as("要素⑤后值快照").isNotNull();
        assertThat(latest.getIp()).as("要素③何处：IP").isEqualTo("127.0.0.1");
        assertThat(latest.getActorType()).as("actor 类型（V6 2C）").isEqualTo("TENANT");
        assertThat(latest.getActorId()).as("actor 标识（V6 2C）").isEqualTo("test-app");
        assertThat(latest.getHashVersion()).as("新写入哈希算法版本=2（actor 列参与哈希）")
                .isEqualTo(AuditService.HASH_VERSION_CURRENT);
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
    @DisplayName("T3: 篡改一条审计记录的内容 → 重算哈希与存储值不匹配（篡改可检出；@AfterEach 还原）")
    void t3_tamperingDetected() throws Exception {
        produceAuditRecord("T3");
        List<AuditLogPO> records = waitForAuditRecordsByAction("TEST_T3");
        assertThat(records).as("本测试写过一条 TEST_T3 记录").isNotEmpty();

        AuditLogPO victim = records.get(records.size() - 1);
        tamperedRowId = victim.getId();          // @AfterEach 依据（M4：篡改行必须还原）
        tamperedOriginalAction = victim.getAction();

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

    /**
     * T4（V6 2C）：两线程并发 record → 链连续无分叉。
     *
     * <p>串行化机制是 V35 的 audit_chain_head 单行 FOR UPDATE（跨实例也成立）；
     * v2 口径（actor 列进哈希）换了 writeRecord 的拼接内容但没动加锁路径——
     * 本用例把这个性质钉住：并发写入后全表按 id 升序 prev_hash 逐条衔接，
     * 且不存在两条记录 prev_hash 相同的分叉（分叉=两个写者读了同一个链头）。
     */
    @Test
    @Order(4)
    @DisplayName("T4: 两线程并发 record 各 10 条 → 全链按 id 衔接、无分叉")
    void t4_concurrentWritesChainStaysContinuous() throws Exception {
        int perThread = 10;
        Runnable writer = () -> {
            for (int i = 0; i < perThread; i++) {
                auditService.record(AuditService.AuditContext.of(
                        "test-app", 200L, "trace-t4",
                        "TEST_T4", "TEST_RESOURCE", "r-t4",
                        "{\"b\":1}", "{\"a\":1}",
                        "127.0.0.1", 0,
                        "TENANT", "test-app", null));
            }
        };
        Thread t1 = new Thread(writer, "audit-t4-a");
        Thread t2 = new Thread(writer, "audit-t4-b");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // 等两线程全部落库（共 20 条 TEST_T4）
        List<AuditLogPO> records = List.of();
        for (int i = 0; i < 50; i++) {
            records = waitForAuditRecordsByAction("TEST_T4");
            if (records.size() >= 2 * perThread) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(records).as("两线程各 10 条应全部落库").hasSize(2 * perThread);

        records.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        // 无分叉：任何 record_hash 至多被一个后继引用（两个后继=两条链，FOR UPDATE 失效）
        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (int i = 1; i < records.size(); i++) {
            AuditLogPO prev = records.get(i - 1);
            AuditLogPO cur = records.get(i);
            assertThat(cur.getPrevHash())
                    .as("id=%d 的 prev_hash 应等于前一条 id=%d 的 record_hash",
                            cur.getId(), prev.getId())
                    .isEqualTo(prev.getRecordHash());
            assertThat(referenced.add(cur.getPrevHash()))
                    .as("record_hash 被 %d 引用两次——链分叉（FOR UPDATE 串行失效）", prev.getId())
                    .isTrue();
        }
    }
}
