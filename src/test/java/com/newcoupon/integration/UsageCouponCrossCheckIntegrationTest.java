package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.StockReconcileService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L4 占用单×券状态交叉核对验收（2026-09-15，docs/方案待批）。
 *
 * <p>问题（运行时复现）：现有对账只对库存维度（Redis 三本账恒等式、DB 账本↔明细互证），
 * 没有任何检查覆盖"券状态 ↔ 占用单状态"这一族一致性：
 *   券=已锁定 却没有占用中的合同（锁悬挂）；合同=占用中 券却不是锁定（合同悬挂）。
 * 成因可以是历史 bug、人工改库、未发现的窗口——没人查就永远沉默。
 *
 * <p>期望：日审新增交叉核对（双向），不一致落 stock_drift_log（新动作类型）+告警。
 * 注入方式=裸 SQL 直改一边状态，模拟"人工改库/历史残留"。
 */
@SpringBootTest
class UsageCouponCrossCheckIntegrationTest extends ShardedIntegrationTestSupport {

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
    private TradeAppService tradeAppService;
    @Autowired
    private StockReconcileService reconcileService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger USER_SEQ = new AtomicInteger(95_000);
    private static final AtomicInteger ORD_SEQ = new AtomicInteger(1);

    /** 领一张券并锁定，返回 [couponId, userId, usageNo] */
    private String[] lockedCoupon(String tag) {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "交叉核对券", 1004L, 1, 1000L, null, 10000L, 1000L, 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, null, null));
        templateAppService.publish(t.id());
        long uid = USER_SEQ.incrementAndGet();
        ReceiveResult r = receiveAppService.receive(
                new ReceiveRequest("l4-" + tag + "-" + uid, uid, t.id()));
        LockResult lock = tradeAppService.lock(new LockRequest(
                "ORD-L4-" + tag + "-" + ORD_SEQ.incrementAndGet(), uid, r.couponId()));
        return new String[]{String.valueOf(r.couponId()), String.valueOf(uid), lock.usageNo()};
    }

    private int driftCount(String action, long couponId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_drift_log WHERE action = ? AND db_issued >= 0 "
                        + "AND detail LIKE CONCAT('%', ?, '%')",
                Integer.class, action, String.valueOf(couponId));
        return n == null ? 0 : n;
    }

    @Test
    @Timeout(120)
    @DisplayName("L4①【红灯·正向】：合同占用中但券已不是锁定态 → 日审应落 USAGE_HANGS 漂移")
    void occupyingUsageWithNonLockedCouponDetected() {
        String[] c = lockedCoupon("a");
        long couponId = Long.parseLong(c[0]);
        // 注入不一致：券被改回"待使用"（人工改库/历史残留），占用单仍"占用中"；
        // 占用时间推老 1 小时——A 侧只核对"占用超10分钟"的合同（在途排除，评审#7）
        jdbc.update("UPDATE coupon_usage SET occupy_time = NOW() - INTERVAL 1 HOUR WHERE usage_no = ?", c[2]);
        jdbc.update("UPDATE user_coupon SET status = 0 WHERE id = ?", couponId);

        reconcileService.dailyAudit();   // 现有日审（现状：只查库存维度，对此沉默）

        assertThat(driftCount("USAGE_HANGS", couponId))
                .as("占用中的合同对应的券不是锁定态——交叉核对应发现并留痕").isGreaterThan(0);
    }

    @Test
    @Timeout(120)
    @DisplayName("L4②【红灯·反向】：券锁定中但没有占用中的合同 → 日审应落 LOCK_HANGS 漂移")
    void lockedCouponWithoutOccupyingUsageDetected() {
        String[] c = lockedCoupon("b");
        long couponId = Long.parseLong(c[0]);
        // 注入不一致：占用单被改成"已释放"，券仍"已锁定"（B 侧候选；点查复核确认）
        jdbc.update("UPDATE coupon_usage SET status = 2 WHERE usage_no = ?", c[2]);

        reconcileService.dailyAudit();

        assertThat(driftCount("LOCK_HANGS", couponId))
                .as("锁定中的券却没有占用中的合同——交叉核对应发现并留痕").isGreaterThan(0);
    }

    @Test
    @Timeout(120)
    @DisplayName("L4③【控制组】：一致的锁定对不误报")
    void consistentPairNotReported() {
        String[] c = lockedCoupon("ctrl");

        reconcileService.dailyAudit();

        assertThat(driftCount("USAGE_HANGS", Long.parseLong(c[0]))).isZero();
        assertThat(driftCount("LOCK_HANGS", Long.parseLong(c[0]))).isZero();
    }
}
