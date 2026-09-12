package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.MyCoupon;
import com.newcoupon.application.service.CouponExpireService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * L2 券过期全链路回归（2026-09-12 实施后；前身 ExpiryBugReproductionTest 的断言已全部翻转——
 * 复现期钉住的三个错误行为：状态悬挂/核销成功(资损)/仅锁定有守卫，现在分别被
 * 惰性判断+扫描收敛、核销谓词+EXPIRE_ON_LOCK 收场、全链路守卫取代）。
 *
 * <p>验收对应技术方案 §1：A1 过期券任何入口不可用；A2 券包不显示过期券为待使用；
 * A3 EXPIRED 事件落表；A4/A5 任务表小批量+幂等（重跑零重复流转）。
 */
@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=50")
class ExpiryIntegrationTest {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private TemplateAppService templateAppService;
    @Autowired
    private ReceiveAppService receiveAppService;
    @Autowired
    private TradeAppService tradeAppService;
    @Autowired
    private CouponExpireService couponExpireService;
    @Autowired
    private UserCouponRepository userCouponRepository;
    @Autowired
    private CouponUsageRepository couponUsageRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger USER_SEQ = new AtomicInteger(70_000);

    /** 建券发布并领到一张券（有效期 30 天），返回 (couponId, userId) */
    private long[] freshCoupon(String tag) {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "过期回归券", 1001L, 1, 1000L, null, 10000L, 1000L, 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, null, null));
        templateAppService.publish(t.id());
        long userId = USER_SEQ.incrementAndGet();
        ReceiveResult r = receiveAppService.receive(
                new ReceiveRequest("exp-" + tag + "-" + userId, userId, t.id()));
        return new long[]{r.couponId(), userId};
    }

    /**
     * 时间穿越到过期之后：同时把券的 valid_end 和过期任务的 next_scan_time 都改到两天前——
     * 生产里时间流逝时两者一起到点（任务时间=领取时固化的有效期终点），只改一处是模拟失真。
     * 用两天而非两秒：容器 UTC 与 JVM +08 差 8h，远离边界不受时区影响。
     */
    private void expireNow(long couponId) {
        LocalDateTime past = LocalDateTime.now().minusDays(2);
        assertThat(jdbc.update("UPDATE user_coupon SET valid_end = ? WHERE id = ?", past, couponId))
                .isEqualTo(1);
        jdbc.update("UPDATE coupon_expire_task SET next_scan_time = ? WHERE coupon_id = ?",
                past, couponId);
    }

    private int expiredEventCount(long couponId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon_event WHERE user_coupon_id = ? AND event_type = 5",
                Integer.class, couponId);
        return n == null ? 0 : n;
    }

    @Test
    @Timeout(120)
    @DisplayName("L2①：领券即注册过期任务（同事务），next_scan_time=有效期终点")
    void taskRegisteredOnIssue() {
        long[] ids = freshCoupon("reg");

        String nextScanDay = jdbc.queryForObject(
                "SELECT DATE_FORMAT(next_scan_time, '%Y-%m-%d') FROM coupon_expire_task WHERE coupon_id = ?",
                String.class, ids[0]);
        String validEndDay = jdbc.queryForObject(
                "SELECT DATE_FORMAT(valid_end, '%Y-%m-%d') FROM user_coupon WHERE id = ?",
                String.class, ids[0]);

        assertThat(nextScanDay).as("领券同事务注册任务，扫描时间=有效期终点").isEqualTo(validEndDay);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM coupon_expire_task WHERE coupon_id = ?", Integer.class, ids[0]))
                .isEqualTo(0);
    }

    @Test
    @Timeout(120)
    @DisplayName("L2②【复现①翻转】：扫描前状态列未动，但券包按'已过期'渲染（惰性判断不写库）")
    void lazyDisplayBeforeScan() {
        long[] ids = freshCoupon("lazy");
        expireNow(ids[0]);

        MyCoupon viewed = tradeAppService.myCoupons(ids[1], null).coupons().stream()
                .filter(c -> c.id() == ids[0]).findFirst().orElseThrow();

        assertThat(viewed.status()).as("展示层惰性判断：过期券显示已过期").isEqualTo("已过期");
        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .as("查询无副作用：物理状态仍待使用，由扫描收敛").isEqualTo(CouponStatus.USABLE);
    }

    @Test
    @Timeout(120)
    @DisplayName("L2③：扫描收敛物理状态+落事件；重跑幂等零重复")
    void scannerTransitionsAndIdempotent() {
        long[] a = freshCoupon("scan-a");
        long[] b = freshCoupon("scan-b");
        expireNow(a[0]);
        expireNow(b[0]);

        int processed = couponExpireService.scanOnce();

        assertThat(processed).as("两张到期任务被处理").isGreaterThanOrEqualTo(2);
        assertThat(userCouponRepository.findById(a[0]).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.EXPIRED);
        assertThat(expiredEventCount(a[0])).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM coupon_expire_task WHERE coupon_id = ?", Integer.class, a[0]))
                .as("任务已终结").isEqualTo(1);

        couponExpireService.scanOnce();   // 重跑：任务已终结，捞不到；即使重复触发 CAS 也影响 0 行

        assertThat(expiredEventCount(a[0])).as("幂等：无重复事件").isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("L2④【复现②保持】：过期未锁的券锁定被拒（4002，谓词守卫）")
    void expiredCouponCannotBeLocked() {
        long[] ids = freshCoupon("lock");
        expireNow(ids[0]);
        try {
            tradeAppService.lock(new LockRequest("ORD-EXP-LOCK", ids[1], ids[0]));
            throw new AssertionError("过期券锁定应被拒绝");
        } catch (BizException e) {
            assertThat(e.getErrorCode().getCode()).isEqualTo(4002);
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("L2⑤【复现③翻转·核心】：锁定后过期再核销 → 4005拒绝+EXPIRE_ON_LOCK收场+事件")
    void confirmOnExpiredLockedRejectedAndCollected() {
        long[] ids = freshCoupon("confirm");
        LockResult lock = tradeAppService.lock(new LockRequest("ORD-EXP-CF", ids[1], ids[0]));
        expireNow(ids[0]);

        try {
            tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), 1000L));
            throw new AssertionError("过期券核销应被拒绝");
        } catch (BizException e) {
            assertThat(e.getErrorCode()).as("明确的业务错误码，而非笼统状态冲突")
                    .isEqualTo(ErrorCode.COUPON_EXPIRED);
        }

        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .as("券收终态：已过期").isEqualTo(CouponStatus.EXPIRED);
        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .as("占用单第三种终结：过期关闭（非核销非释放）").isEqualTo(UsageStatus.EXPIRED);
        assertThat(expiredEventCount(ids[0])).as("EXPIRED 事件落表（含订单号供对账）").isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("L2⑥：扫描器也收锁定中过期（用户不再来核销时的兜底收场）")
    void scannerCollectsLockedExpired() {
        long[] ids = freshCoupon("scan-lock");
        LockResult lock = tradeAppService.lock(new LockRequest("ORD-EXP-SL", ids[1], ids[0]));
        expireNow(ids[0]);

        couponExpireService.scanOnce();

        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.EXPIRED);
        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .isEqualTo(UsageStatus.EXPIRED);
    }

    @Test
    @Timeout(120)
    @DisplayName("L2⑦【控制组】：有效券的锁定/核销不受新谓词影响（守卫不过度拦截）")
    void validCouponFlowUnaffected() {
        long[] ids = freshCoupon("valid");
        LockResult lock = tradeAppService.lock(new LockRequest("ORD-VALID", ids[1], ids[0]));
        var confirm = tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), 1000L));

        assertThat(confirm.couponStatus()).isEqualTo("已核销");
        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USED);
    }

    @Test
    @Timeout(120)
    @DisplayName("L2⑧【复现①物理态翻转】：扫描后券包与数据库状态一致（无需惰性判断参与）")
    void physicalStateConsistentAfterScan() {
        long[] ids = freshCoupon("after");
        expireNow(ids[0]);
        couponExpireService.scanOnce();

        UserCoupon coupon = userCouponRepository.findById(ids[0]).orElseThrow();
        MyCoupon viewed = tradeAppService.myCoupons(ids[1], null).coupons().stream()
                .filter(c -> c.id() == ids[0]).findFirst().orElseThrow();

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.EXPIRED);
        assertThat(viewed.status()).isEqualTo("已过期");
    }
}
