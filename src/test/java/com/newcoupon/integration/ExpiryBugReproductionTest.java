package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UserCoupon;
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
 * 【L2 复现专档】券过期链路缺失的实证（2026-09-12，修复前快照）。
 *
 * <p>本类故意断言【当前错误行为】——三条测试全绿 = 三个症状全部真实存在。
 * L2 修复落地后，本类断言将翻转（过期券：券包显示已过期 / 锁定被拒 / 核销被拒），
 * 届时改写为正式回归测试。设计依据：docs/06 整章（零实现）；状态机 EXPIRE/EXPIRE_ON_LOCK
 * 边已定义但无任何触发者。
 *
 * <p>复现手段：领到真券后用 JdbcTemplate 直改 valid_end 到过去——等价于"时间流逝到过期"，
 * 避免测试等待真实时间。
 */
@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=50")
class ExpiryBugReproductionTest {

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
    private UserCouponRepository userCouponRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger USER_SEQ = new AtomicInteger(70_000);

    /** 建券发布并领到一张券（绝对有效期 30 天），返回 (couponId, userId) */
    private long[] freshCoupon() {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "过期复现券", 1001L, 1, 1000L, null, 10000L, 1000L, 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, null, null));
        templateAppService.publish(t.id());
        long userId = USER_SEQ.incrementAndGet();
        ReceiveResult r = receiveAppService.receive(
                new ReceiveRequest("expire-prep-" + userId, userId, t.id()));
        return new long[]{r.couponId(), userId};
    }

    /**
     * 把券的 valid_end 改到过去——等价于时间流逝到过期之后。
     * 用 minusDays(2) 而非减几秒：测试容器 MySQL 是 UTC、JVM 是 +08:00（本次复现实测发现，
     * 两侧时钟差 8 小时）——减 2 天对两个时钟都无歧义地在过去，复现不被环境时区干扰。
     * （生产部署必须统一 JVM/DB 时区，此坑已登记进 L2 设计注意事项。）
     */
    private void expireNow(long couponId) {
        int n = jdbc.update("UPDATE user_coupon SET valid_end = ? WHERE id = ?",
                LocalDateTime.now().minusDays(2), couponId);
        assertThat(n).as("直改 valid_end 应命中一行").isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("复现①：过期后券包仍显示'待使用'——状态悬挂，无惰性判断、无定时任务")
    void expiredCouponStillShowsUsable() {
        long[] ids = freshCoupon();
        expireNow(ids[0]);

        UserCoupon viewed = userCouponRepository.findById(ids[0]).orElseThrow();

        System.out.println("[复现①] 过期券在券包里的状态 = " + viewed.getStatus()
                + "（0=待使用）——用户看到一张能用的券，实际已过期");
        assertThat(viewed.getStatus())
                .as("当前错误行为：状态停留在 USABLE，EXPIRE 转移从未被触发（修复后应为 EXPIRED 或查询时惰性判定）")
                .isEqualTo(CouponStatus.USABLE);
    }

    @Test
    @Timeout(120)
    @DisplayName("复现②（半个好消息）：过期未锁的券锁定会被拒——casLock 的 valid_end 守卫生效")
    void expiredCouponCannotBeLocked() {
        long[] ids = freshCoupon();
        expireNow(ids[0]);

        String code = tryLock(ids[1], ids[0]);

        System.out.println("[复现②] 过期券 lock 结果 = " + code
                + "——现有防线边界：锁得住的入口有守卫，问题在别处（见复现③）");
        assertThat(code).as("casLock WHERE valid_end > NOW() 生效，锁定被拒").isNotEqualTo("OK");
    }

    @Test
    @Timeout(120)
    @DisplayName("复现③【资损实锤】：锁定后过期的券仍能核销——casConfirm 无过期守卫")
    void expiredLockedCouponCanBeConfirmed() {
        long[] ids = freshCoupon();
        long couponId = ids[0];
        long userId = ids[1];

        // 先在有效期内锁定（合法路径）
        LockResult lock = tradeAppService.lock(new LockRequest("ORD-EXP", userId, couponId));
        assertThat(lock.usageNo()).isNotBlank();

        // 时间流逝：锁定期间券到期（下单后 20 分钟未支付，券是 1 天前到期的极端形态）
        expireNow(couponId);

        // 支付回调到达——核销一张已过期的券
        var confirm = tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), 1000L));

        UserCoupon after = userCouponRepository.findById(couponId).orElseThrow();
        System.out.println("[复现③] 已过期券核销结果 = " + confirm.couponStatus()
                + "，券终态 = " + after.getStatus() + "（2=已核销）"
                + "——平台为一张过期券付了 1000 分补贴，且占用单 CONFIRMED 入账");
        assertThat(confirm.couponStatus())
                .as("当前错误行为：核销成功（修复后应拒绝或按过期处理，L2 的核心验收点）")
                .isEqualTo("已核销");   // couponStatus 返回的是状态中文描述
        assertThat(after.getStatus()).isEqualTo(CouponStatus.USED);
    }

    private String tryLock(long userId, long couponId) {
        try {
            tradeAppService.lock(new LockRequest("ORD-EXP-LOCK", userId, couponId));
            return "OK";
        } catch (com.newcoupon.common.BizException e) {
            return String.valueOf(e.getErrorCode().getCode());
        }
    }
}
