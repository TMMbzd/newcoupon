package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.service.CouponExpireService;
import com.newcoupon.application.service.DailyBillService;
import com.newcoupon.application.service.PricingSnapshotCleanupService;
import com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper;
import com.newcoupon.infrastructure.redis.RedisScanLock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 多实例调度互斥回归（2026-09-12）：Redis SET NX PX 抢锁 + 比对释放。
 * 验证三件事：①锁互斥（第二个持有者抢不到）②释放只认自己的持有者标识（防误删他人锁）
 * ③没抢到锁的调度轮次让路（scan/巡检直接返回，不产生任何 DB 负载）。
 * 正确性本由幂等 CAS 保证（重复执行不改错数据），锁消的是重复扫描的负载——
 * 这条测试钉住的是"让路行为"本身。
 */
class SchedulerLockIntegrationTest extends ShardedIntegrationTestSupport {

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }


    

    private static final String DAILY_BILL_LOCK = "c:lock:daily-bill";
    private static final String PRICING_CLEANUP_LOCK = "c:lock:pricing-snapshot-cleanup";
    private static final String OTHER_INSTANCE = "another-instance";

    @Autowired
    private RedisScanLock scanLock;
    @Autowired
    private CouponExpireService couponExpireService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DailyBillService dailyBillService;
    @Autowired
    private PricingSnapshotCleanupService pricingSnapshotCleanupService;
    @Autowired
    private CouponDailyBillMapper billMapper;
    @Autowired
    private DataSource dataSource;

    private LocalDate dailyBillDate;
    private long dailyBillTemplateId;
    private String dailyBillUsageNo;
    private String pricingId;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /** 外部实例持锁；后台定时任务可能刚持锁一瞬间，短暂重试避免把调度竞争报成用例失败。 */
    private void holdLockExternally(String lockKey) {
        boolean held = false;
        for (int i = 0; i < 20 && !held; i++) {
            held = scanLock.tryLock(lockKey, OTHER_INSTANCE, Duration.ofSeconds(60));
            if (!held) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        assertThat(held).as("外部实例持有锁 %s", lockKey).isTrue();
    }

    @AfterEach
    void cleanupSchedulerLockFixtures() {
        scanLock.unlock(DAILY_BILL_LOCK, OTHER_INSTANCE);
        scanLock.unlock(PRICING_CLEANUP_LOCK, OTHER_INSTANCE);
        if (dailyBillUsageNo != null) {
            jdbc().update("DELETE FROM coupon_share_detail WHERE usage_no = ?", dailyBillUsageNo);
        }
        if (dailyBillDate != null) {
            jdbc().update("DELETE FROM coupon_daily_bill WHERE bill_date = ? AND template_id = ?",
                    dailyBillDate, dailyBillTemplateId);
            jdbc().update("DELETE FROM coupon_settle_party_daily_bill "
                            + "WHERE bill_date = ? AND template_id = ?",
                    dailyBillDate, dailyBillTemplateId);
        }
        if (pricingId != null) {
            jdbc().update("DELETE FROM coupon_pricing_snapshot WHERE pricing_id = ?", pricingId);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("锁互斥：先到者得；错误的持有者释放不掉；正确的持有者释放后可再抢")
    void mutualExclusionAndOwnerSafeRelease() {
        String key = "test:lock:1";
        redis.delete(key);

        assertThat(scanLock.tryLock(key, "A", Duration.ofSeconds(30)))
                .as("第一个持有者抢到").isTrue();
        assertThat(scanLock.tryLock(key, "B", Duration.ofSeconds(30)))
                .as("第二个持有者被拒").isFalse();

        scanLock.unlock(key, "B");   // B 试图释放 A 的锁——必须失败
        assertThat(redis.hasKey(key)).as("B 释放不掉 A 的锁").isTrue();

        scanLock.unlock(key, "A");   // A 释放自己的锁
        assertThat(redis.hasKey(key)).as("A 释放成功").isFalse();

        assertThat(scanLock.tryLock(key, "C", Duration.ofSeconds(30)))
                .as("释放后可再抢").isTrue();
        scanLock.unlock(key, "C");
    }

    @Test
    @Timeout(60)
    @DisplayName("过期扫描让路：锁被他人持有 → scan() 本轮零处理零 DB 触碰；释放后恢复")
    void expireScanYieldsWhenLockHeld() {
        String lockKey = "c:exp:scanlock:0";
        scanLock.tryLock(lockKey, "another-instance", Duration.ofSeconds(30));

        int processed = couponExpireService.scan();

        assertThat(processed).as("没抢到锁：本轮让路，零处理").isZero();

        scanLock.unlock(lockKey, "another-instance");
        assertThat(couponExpireService.scan())
                .as("锁释放后恢复扫描（无到期任务也正常跑完返回 0）").isZero();
    }

    @Test
    @Timeout(60)
    @DisplayName("日账单让路：外部持有 c:lock:daily-bill → runOnce 返回 0、不写账单、不释放他人锁；释放后恢复")
    void dailyBillRunOnceYieldsWhenLockHeld() {
        // 未来日期 + 唯一模板 + 一条合成正分账行：真跑就一定写出一行账单；
        // 让路则账单表零行、结算主体账单零行。
        dailyBillDate = LocalDate.of(2099, 1, 1)
                .plusDays(ThreadLocalRandom.current().nextInt(0, 365));
        dailyBillTemplateId = 9_900_000_000_000_000L
                + ThreadLocalRandom.current().nextLong(1_000_000L);
        dailyBillUsageNo = "SCHED-DAILY-" + UUID.randomUUID();
        String orderNo = "ORD-SCHED-DAILY-" + UUID.randomUUID();
        long userId = 8_800_000_000_000_000L
                + ThreadLocalRandom.current().nextLong(1_000_000L);
        long userCouponId = 8_700_000_000_000_000L
                + ThreadLocalRandom.current().nextLong(1_000_000L);

        jdbc().update("INSERT INTO coupon_share_detail "
                        + "(user_coupon_id, user_id, template_id, usage_no, order_no, "
                        + " platform_burden, merchant_burden, share_total, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, 1234, 0, 1234, ?)",
                userCouponId, userId, dailyBillTemplateId, dailyBillUsageNo, orderNo,
                dailyBillDate.atTime(12, 0));

        holdLockExternally(DAILY_BILL_LOCK);
        int yielded = dailyBillService.runOnce(dailyBillDate);

        assertThat(yielded).as("锁被他人持有：本轮让路返回 0").isZero();
        assertThat(redis.opsForValue().get(DAILY_BILL_LOCK))
                .as("服务没有释放他人的锁").isEqualTo(OTHER_INSTANCE);
        assertThat(billMapper.selectByDate(dailyBillDate).stream()
                .noneMatch(b -> b.getTemplateId() == dailyBillTemplateId))
                .as("让路轮零模板账单行（没有做聚合落库）").isTrue();
        Long partyRows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_settle_party_daily_bill "
                        + "WHERE bill_date = ? AND template_id = ?",
                Long.class, dailyBillDate, dailyBillTemplateId);
        assertThat(partyRows).as("让路轮零结算主体账单行").isZero();

        scanLock.unlock(DAILY_BILL_LOCK, OTHER_INSTANCE);
        assertThat(redis.hasKey(DAILY_BILL_LOCK)).as("外部锁已释放").isFalse();

        int ran = dailyBillService.runOnce(dailyBillDate);
        assertThat(ran).as("锁释放后恢复生成，返回 1（已生成）").isEqualTo(1);

        var bill = billMapper.selectByDate(dailyBillDate).stream()
                .filter(b -> b.getTemplateId() == dailyBillTemplateId)
                .findFirst().orElseThrow();
        assertThat(bill.getConfirmCount()).as("一条正分账行=核销 1").isEqualTo(1);
        assertThat(bill.getRefundCount()).as("无退款").isZero();
        assertThat(bill.getDiscountTotal()).as("净优惠 1234").isEqualTo(1234L);
        assertThat(bill.getPlatformBurden()).isEqualTo(1234L);
        assertThat(bill.getMerchantBurden()).isZero();
        assertThat(redis.hasKey(DAILY_BILL_LOCK)).as("服务执行后释放自己的锁").isFalse();
    }

    @Test
    @Timeout(60)
    @DisplayName("价格快照清理让路：外部持有 c:lock:pricing-snapshot-cleanup → runOnce 返回 0、不清 live_digest；释放后清理")
    void pricingCleanupRunOnceYieldsWhenLockHeld() {
        pricingId = "P-SCHED-" + UUID.randomUUID().toString().replace("-", "");
        String digest = (UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "")).substring(0, 64);
        long userId = 8_600_000_000_000_000L
                + ThreadLocalRandom.current().nextLong(1_000_000L);
        String orderNo = "ORD-SCHED-PRICE-" + UUID.randomUUID();

        jdbc().update("INSERT INTO coupon_pricing_snapshot "
                        + "(pricing_id, app_id, user_id, order_no, coupon_set, "
                        + " request_digest, live_digest, payload, payload_version, "
                        + " engine_version, payload_bytes, line_count, applied_coupon_count, "
                        + " original_amount, discount_amount, final_amount, expires_at) "
                        + "VALUES (?, '1', ?, ?, '[]', ?, ?, '{}', 1, "
                        + " 'scheduler-lock-test', 2, 0, 0, 0, 0, 0, ?)",
                pricingId, userId, orderNo, digest, digest,
                LocalDateTime.now().minusMinutes(1));

        holdLockExternally(PRICING_CLEANUP_LOCK);
        int yielded = pricingSnapshotCleanupService.runOnce();

        assertThat(yielded).as("锁被他人持有：本轮让路返回 0").isZero();
        assertThat(redis.opsForValue().get(PRICING_CLEANUP_LOCK))
                .as("服务没有释放他人的锁").isEqualTo(OTHER_INSTANCE);
        assertThat(liveDigestOf(pricingId))
                .as("让路轮不清 live_digest（零仓库工作）").isEqualTo(digest);
        assertThat(snapshotCountOf(pricingId)).as("快照行仍在").isEqualTo(1L);

        scanLock.unlock(PRICING_CLEANUP_LOCK, OTHER_INSTANCE);
        assertThat(redis.hasKey(PRICING_CLEANUP_LOCK)).as("外部锁已释放").isFalse();

        // 释放后重新置为过期，确保这一轮确实由本次 runOnce 清理（也防后台清理抢先置空）。
        jdbc().update("UPDATE coupon_pricing_snapshot "
                        + "SET live_digest = ?, expires_at = ? WHERE pricing_id = ?",
                digest, LocalDateTime.now().minusMinutes(1), pricingId);
        int cleaned = pricingSnapshotCleanupService.runOnce();

        assertThat(cleaned).as("锁释放后清理至少处理本行").isGreaterThanOrEqualTo(1);
        assertThat(liveDigestOf(pricingId)).as("过期快照的 live_digest 被置空").isNull();
        assertThat(snapshotCountOf(pricingId))
                .as("未超过 7 天保留期：只置空不物理删除").isEqualTo(1L);
        assertThat(redis.hasKey(PRICING_CLEANUP_LOCK)).as("服务执行后释放自己的锁").isFalse();
    }

    private String liveDigestOf(String id) {
        return jdbc().queryForObject(
                "SELECT live_digest FROM coupon_pricing_snapshot WHERE pricing_id = ?",
                String.class, id);
    }

    private Long snapshotCountOf(String id) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM coupon_pricing_snapshot WHERE pricing_id = ?",
                Long.class, id);
    }
}
