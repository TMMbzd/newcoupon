package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.service.OutboxDispatcher;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P2d 阶段二接线证明（对 compose 的真 RocketMQ，非 testcontainers——MQ 是平台件不随测试起停）。
 *
 * <p>运行方式：compose 起好 MQ 后 {@code mvn test -Dmq.it=true -Dtest=P2dRocketMqWiringTest}。
 * 默认（无 -Dmq.it）跳过——常规套件不依赖 MQ 存活（发件箱阶段一测试仍守"库里有就必有消息"）。
 *
 * <p>证明的闭环：领券 → 发件箱行（同事务）→ dispatcher 投递到真 broker →
 * push 消费者消费 → 幂等锚点表落行。消费端 at-least-once 的重投面由
 * {@code duplicateDelivery} 用例证明（第二次 handle 返回 false=跳过）。
 */
@EnabledIfSystemProperty(named = "mq.it", matches = "true")
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "coupon.reconcile.inflight-ttl-ms=300",
        "coupon.reconcile.log-min-interval-ms=0",
        "coupon.sharding.bucket-size=10000",
        "coupon.sharding.bucket-count=16",
        "coupon.outbox.transport=rocketmq",       // 真 RocketMQ 传输整套装配
        "coupon.outbox.namesrv-addr=localhost:9876"
})
class P2dRocketMqWiringTest extends ShardedIntegrationTestSupport {

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
    private com.newcoupon.application.service.IssueEventHandler handler;
    @Autowired
    private DataSource dataSource;

    private static final AtomicLong USER = new AtomicLong(900_000);

    @Test
    @DisplayName("端到端：领券→发件箱→dispatcher→真broker→消费者→幂等锚点表（≤30s）")
    void fullLoop_throughRealBroker() {
        long tpl = createTemplate();
        String reqId = "mqit-" + System.nanoTime();
        receiveAppService.receive(new ReceiveRequest(reqId, USER.incrementAndGet(), tpl));

        dispatcher.dispatch();   // 投递到真 broker

        // push 消费者异步消费——轮询幂等锚点表（30 秒上限）
        String eventNoSuffix = ":" + reqId;
        boolean consumed = false;
        long deadline = System.currentTimeMillis() + 30_000;
        Integer status = null;
        while (System.currentTimeMillis() < deadline) {
            status = queryConsumed(eventNoSuffix);
            if (status != null) { consumed = true; break; }
            sleep(500);
        }
        assertThat(consumed).as("事件经真 broker 到达消费端并落幂等锚点表").isTrue();

        // 消费端重投面：同一 eventNo 再 handle → 幂等跳过（at-least-once 上的 effectively-once）
        assertThat(handler.handle("coupon-issued", eventNoOf(eventNoSuffix), "{}"))
                .as("重投必须被幂等跳过（返回 false）").isFalse();
    }

    private Integer queryConsumed(String eventNoSuffix) {
        try {
            return new JdbcTemplate(dataSource).queryForObject(
                    "SELECT 1 FROM coupon_event_consumed WHERE event_no LIKE '%\""
                            .replace("\"", "") + eventNoSuffix + "'", Integer.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    private String eventNoOf(String suffix) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT event_no FROM coupon_event_consumed WHERE event_no LIKE '%" + suffix + "'",
                String.class);
    }

    private long createTemplate() {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "MQ接线券", 1001L, 1, 1000L, null, 10000L, 100L,
                1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null,
                null, null));
        templateAppService.publish(t.id());
        return t.id();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
