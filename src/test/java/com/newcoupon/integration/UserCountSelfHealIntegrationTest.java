package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L3 用户限领计数自愈验收（2026-09-15，docs/方案待批）。
 *
 * <p>问题（运行时复现）：Redis 的用户累计计数（c:ul）是内存数据，失忆（重启/清库/故障切换）
 * 后归零——闸门以为用户一张没领过。失忆不是超发（DB 的 uk_user_seq 唯一索引兜底），
 * 而是**合法领取被永久拒绝**：序号从 1 重数、每次都撞已有索引、每次都被当"已达限领"拒绝，
 * 且计数永远错着不自愈（classifyAfterRollback 直接抛 3002，不做任何修复）。
 *
 * <p>期望（自愈后）：撞 uk_user_seq 时以 DB 行数为权威——行数已达限领则重置计数并正确拒绝；
 * 行数未达限领则重置计数并**透明重试本次领取**（用户无感）。
 */
@SpringBootTest
class UserCountSelfHealIntegrationTest extends ShardedIntegrationTestSupport {

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
    private ReceiveAppService receiveAppService;
    @Autowired
    private RedisStockGateway stockGateway;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger USER_SEQ = new AtomicInteger(90_000);

    /** 建模板（限领 limitPerUser 张）并发布，返回模板 id */
    private long template(int limitPerUser) {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "计数自愈券", 1003L, 1, 1000L, null, 10000L, 1000L, limitPerUser, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, null, null));
        templateAppService.publish(t.id());
        return t.id();
    }

    private int dbRows(long tplId, long uid) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_coupon WHERE user_id = ? AND template_id = ?",
                Integer.class, uid, tplId);
        return n == null ? 0 : n;
    }

    @Test
    @Timeout(120)
    @DisplayName("L3①【红灯主场景】：失忆后合法领取（2/3 领第3张）应透明自愈成功，计数恢复、DB 三行")
    void counterLossSelfHealsAndThirdReceiveSucceeds() {
        long tplId = template(3);
        long uid = USER_SEQ.incrementAndGet();
        receiveAppService.receive(new ReceiveRequest("l3-a-" + uid, uid, tplId));
        receiveAppService.receive(new ReceiveRequest("l3-b-" + uid, uid, tplId));
        assertThat(stockGateway.userCount(tplId, uid)).isEqualTo(2);

        stockGateway.resetUserCount(tplId, uid);   // 模拟 Redis 失忆（测试与运维用接口）

        ReceiveResult r3 = receiveAppService.receive(new ReceiveRequest("l3-c-" + uid, uid, tplId));

        assertThat(r3.duplicated())
                .as("限领3已领2：第3张是合法领取，自愈后应成功（现状：被当超领永久拒绝）").isFalse();
        assertThat(stockGateway.userCount(tplId, uid))
                .as("计数自愈为 DB 权威值+本次=3（现状：回滚后仍是 0，永远错着）").isEqualTo(3);
        assertThat(dbRows(tplId, uid)).isEqualTo(3);
    }

    @Test
    @Timeout(120)
    @DisplayName("L3②【控制组】：真达到限领的用户仍被正确拒绝，但计数同样被自愈")
    void genuinelyAtLimitStillRejectedButCounterHealed() {
        long tplId = template(2);
        long uid = USER_SEQ.incrementAndGet();
        receiveAppService.receive(new ReceiveRequest("l3-d-" + uid, uid, tplId));
        receiveAppService.receive(new ReceiveRequest("l3-e-" + uid, uid, tplId));

        stockGateway.resetUserCount(tplId, uid);   // 失忆

        try {
            receiveAppService.receive(new ReceiveRequest("l3-f-" + uid, uid, tplId));
            throw new AssertionError("限领2已领2：第3张应被拒绝");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_LIMIT_EXCEEDED);
        }

        assertThat(stockGateway.userCount(tplId, uid))
                .as("拒绝的同时计数也应被重置为 DB 行数——下次闸门在 Redis 层就拦住，不再白撞索引")
                .isEqualTo(2);
        assertThat(dbRows(tplId, uid)).isEqualTo(2);
    }


    @Test
    @Timeout(120)
    @DisplayName("L3③【评审#1回归·seq空洞】：COUNT+1 位已被占时，自愈用 MAX(seq) 保证重试落在空位")
    void seqHoleDoesNotDeadlockSelfHeal() {
        long tplId = template(5);
        long uid = USER_SEQ.incrementAndGet();
        for (int i = 1; i <= 3; i++) {
            receiveAppService.receive(new ReceiveRequest("l3-hole-" + i + "-" + uid, uid, tplId));
        }
        // 制造持久空洞：删掉 seq=2 的行（模拟补偿路径泄漏在途计数后某请求在高位成功）——
        // 行集 {1,3}：COUNT=2（限领判断用），MAX=3（自愈写入值用，旧实现用 COUNT 会让
        // 重试 seq=3 撞在 MAX 行上、retryOnce 耗尽、永久 3002——评审#1 的死循环）
        jdbc.update("DELETE FROM user_coupon WHERE user_id = ? AND template_id = ? AND seq = 2", uid, tplId);

        stockGateway.resetUserCount(tplId, uid);   // 失忆

        ReceiveResult r = receiveAppService.receive(new ReceiveRequest("l3-hole-4-" + uid, uid, tplId));

        assertThat(r.duplicated()).as("空洞存在时自愈取 MAX(seq)：重试 seq=4 必为空位，成功").isFalse();
        assertThat(stockGateway.userCount(tplId, uid)).isEqualTo(4);
        assertThat(dbRows(tplId, uid)).as("3-1+1=3 行").isEqualTo(3);
    }
}
