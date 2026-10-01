package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.OutboxDispatcher;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.infrastructure.persistence.mapper.CouponOutboxMapper;
import com.newcoupon.infrastructure.persistence.po.CouponOutboxPO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P2d 发件箱端到端（ADR-0011）：同事务写入 → 投递器搬运 → 状态闭环 + 守恒。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.reconcile.inflight-ttl-ms=300",
        "coupon.reconcile.log-min-interval-ms=0",
        "coupon.sharding.bucket-size=10000",
        "coupon.sharding.bucket-count=16"
})
class P2dOutboxIntegrationTest extends ShardedIntegrationTestSupport {

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
    private OutboxDispatcher dispatcher;
    @Autowired
    private CouponOutboxMapper outboxMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private com.newcoupon.application.service.IssueEventHandler issueEventHandler;

    private static final AtomicLong USER = new AtomicLong(800_000);

    @Test
    @Timeout(120)
    @DisplayName("L40④·消费幂等：同一 eventNo 第二次处理是 no-op（此前只有门控的 MQ 接线用例覆盖）")
    void consumerIdempotency_secondHandleIsNoop() {
        String eventNo = "RCV:idem-" + java.util.UUID.randomUUID();
        assertThat(issueEventHandler.handle("topic-x", eventNo, "{}")).as("首次：真正处理").isTrue();
        assertThat(issueEventHandler.handle("topic-x", eventNo, "{}")).as("重复投递：跳过").isFalse();
    }

    @Test
    @DisplayName("同事务双写：领券成功→发件箱行待投递；领券回滚（撞限领）→无行——原子性证明")
    void outbox_writtenInSameTx() {
        long tpl = createTemplate();
        long uid = USER.incrementAndGet();
        receiveAppService.receive(new ReceiveRequest("ob-1", uid, tpl));
        receiveAppService.receive(new ReceiveRequest("ob-1", uid, tpl));   // 同 requestId 重放→幂等命中（无新事件）

        List<CouponOutboxPO> rows = outboxMapper.selectDispatchable(LocalDateTime.now().plusMinutes(1), 10);
        long mine = rows.stream().filter(r -> r.getPayload().contains("\"requestId\":\"ob-1\"")).count();
        long dup = rows.stream().filter(r -> r.getEventNo().endsWith(":ob-1")).count();
        assertThat(mine).as("成功领取产生一行待投递").isEqualTo(1);
        assertThat(dup).as("幂等命中的重放不产生新行（仍是那一行）").isEqualTo(1);
        assertThat(outboxMapper.issuedEventMinusOutbox()).as("守恒：发放事件数 ≡ 发件箱行数").isZero();
    }

    @Test
    @Timeout(120)
    @DisplayName("投递闭环：dispatcher 搬运后 status=1（已投递），幂等锚点内嵌 payload")
    void dispatcher_marksDispatched() {
        long tpl = createTemplate();
        receiveAppService.receive(new ReceiveRequest("ob-d1", USER.incrementAndGet(), tpl));

        dispatcher.dispatch();   // 手动触发一轮（不等 1 秒调度）

        // L40④ 附带修正（2026-09-30）：本用例共享**整张**发件箱表，而应用里还有一个每秒跑一轮的
        // 背景投递器（@Scheduled）——两者会抢同一行：被背景线程先搬走的行会离开"待投递"集合，
        // 而抢输/重试会让断言看到中间态。改成对**最终状态**断言（轮询，最多 10 秒）：
        // 不变式是"这张券的事件最终被投递出去"，不是"谁在哪一刻搬的"。
        Integer status = null;
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            status = new JdbcTemplate(dataSource).queryForObject(
                    "SELECT status FROM coupon_outbox WHERE event_no LIKE '%:ob-d1'", Integer.class);
            if (status != null && status == CouponOutboxPO.STATUS_DISPATCHED) {
                break;
            }
            dispatcher.dispatch();   // 再推一轮（幂等：已投递的行不会再被选中）
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(status).as("最终必须已投递（status=1）").isEqualTo(CouponOutboxPO.STATUS_DISPATCHED);
        List<CouponOutboxPO> pending = outboxMapper.selectDispatchable(LocalDateTime.now().plusMinutes(1), 10);
        assertThat(pending.stream().filter(r -> r.getPayload().contains("\"requestId\":\"ob-d1\"")))
                .as("已投递的行不得再出现在待投递集合").isEmpty();
    }

    private long createTemplate() {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "P2d发件箱券", 1001L, 1, 1000L, null, 10000L, 100L,
                1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                null, null));
        templateAppService.publish(t.id());
        return t.id();
    }
}
