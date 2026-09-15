package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.ReleaseRequest;
import com.newcoupon.application.dto.TradeDtos.ReleaseResult;
import com.newcoupon.application.service.CouponExpireService;
import com.newcoupon.application.service.CouponReleaseService;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.infrastructure.persistence.CouponReleaseTaskGateway;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L5 锁定超时兜底验收（2026-09-15，docs/L5 技术方案 T1-T10）。
 *
 * <p>核心场景：锁券后订单侧沉默（测试即"什么都不做"——沉默本身就是要复现的故障），
 * 到窗后扫描收场。测试属性 coupon.trade.lock-timeout-minutes=0 使任务注册即到期
 * （deadline=占用时间+0），免去真实等待 30 分钟。
 *
 * <p>T9 复现 F1（expire 扫描器半收场残局的补刀）；T10 复现 F2（合同终结后重放锁券的谎报）。
 */
@SpringBootTest(properties = {"coupon.trade.lock-timeout-minutes=0"})
class LockTimeoutIntegrationTest extends ShardedIntegrationTestSupport {

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
    private CouponReleaseService couponReleaseService;
    @Autowired
    private CouponExpireService couponExpireService;
    @Autowired
    private CouponReleaseTaskGateway releaseTaskGateway;
    @Autowired
    private com.newcoupon.domain.repository.UserCouponRepository userCouponRepository;
    @Autowired
    private com.newcoupon.domain.repository.CouponUsageRepository couponUsageRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger USER_SEQ = new AtomicInteger(80_000);
    private static final AtomicInteger ORD_SEQ = new AtomicInteger(1);

    /** 建券发布并领一张券（有效期 30 天），返回 [couponId, userId] */
    private long[] freshCoupon(String tag) {
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "锁定超时券", 1002L, 1, 1000L, null, 10000L, 1000L, 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null, null, null));
        templateAppService.publish(t.id());
        long userId = USER_SEQ.incrementAndGet();
        ReceiveResult r = receiveAppService.receive(
                new ReceiveRequest("l5-" + tag + "-" + userId, userId, t.id()));
        return new long[]{r.couponId(), userId};
    }

    private LockResult lock(long couponId, long userId, String tag) {
        return tradeAppService.lock(new LockRequest("ORD-L5-" + tag + "-" + ORD_SEQ.incrementAndGet(),
                userId, couponId));
    }

    private int taskStatus(String usageNo) {
        Integer s = jdbc.queryForObject(
                "SELECT status FROM coupon_release_task WHERE usage_no = ?", Integer.class, usageNo);
        return s == null ? -1 : s;
    }

    /** 把任务标记为"已到窗"（NOW() 相对量——免疫 JVM/容器钟差与 ±8h 时区错位，同过期测试的哲学） */
    private void dueNow(String usageNo) {
        jdbc.update("UPDATE coupon_release_task SET next_scan_time = NOW() - INTERVAL 1 MINUTE "
                + "WHERE usage_no = ?", usageNo);
    }

    /** 把任务推到未来（控制组：时间守卫） */
    private void dueLater(String usageNo) {
        jdbc.update("UPDATE coupon_release_task SET next_scan_time = NOW() + INTERVAL 2 DAY "
                + "WHERE usage_no = ?", usageNo);
    }

    /** 把券改为"已过期"（NOW() 相对量） */
    private void expireCoupon(long couponId) {
        jdbc.update("UPDATE user_coupon SET valid_end = NOW() - INTERVAL 1 DAY WHERE id = ?", couponId);
    }

    private int lockTimeoutEventCount(long couponId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon_event WHERE user_coupon_id = ? AND event_type = 7",
                Integer.class, couponId);
        return n == null ? 0 : n;
    }

    @Test
    @Timeout(120)
    @DisplayName("T1【主场景】：锁→沉默→到窗→扫描 → 券回待使用、占用单超时关闭(4)、事件7落表、任务终结")
    void timeoutClosesSilentLock() {
        long[] ids = freshCoupon("main");
        LockResult lock = lock(ids[0], ids[1], "main");
        dueNow(lock.usageNo());

        int processed = couponReleaseService.scanOnce(0, 1);

        assertThat(processed).as("到窗任务被处理").isGreaterThanOrEqualTo(1);
        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .as("券回池：待使用（未过期走 IF 主路）").isEqualTo(CouponStatus.USABLE);
        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .as("占用单第四种终结：超时关闭").isEqualTo(UsageStatus.TIMEOUT_CLOSED);
        assertThat(lockTimeoutEventCount(ids[0])).as("LOCK_TIMEOUT 事件恰好一条").isEqualTo(1);
        assertThat(taskStatus(lock.usageNo())).as("任务已终结").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT order_no FROM coupon_event WHERE user_coupon_id = ? AND event_type = 7",
                String.class, ids[0])).as("事件带订单号供对账归因").isNotBlank();
    }

    @Test
    @Timeout(120)
    @DisplayName("T2：到窗且券已过期 → 收场为 已过期+超时关闭（IF 分流）")
    void timeoutWithExpiredCouponGoesExpired() {
        long[] ids = freshCoupon("exp");
        LockResult lock = lock(ids[0], ids[1], "exp");
        expireCoupon(ids[0]);
        dueNow(lock.usageNo());

        couponReleaseService.scanOnce(0, 1);

        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .as("已过期的券收终态，不退回可用").isEqualTo(CouponStatus.EXPIRED);
        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .isEqualTo(UsageStatus.TIMEOUT_CLOSED);
    }

    @Test
    @Timeout(120)
    @DisplayName("T3【控制组】：未到窗（next_scan_time 推到未来）→ 扫描不动作（时间守卫）")
    void notYetDueIsUntouched() {
        long[] ids = freshCoupon("ctrl");
        LockResult lock = lock(ids[0], ids[1], "ctrl");
        dueLater(lock.usageNo());

        couponReleaseService.scanOnce(0, 1);

        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .as("不到点任务物理上不可见，券保持锁定").isEqualTo(CouponStatus.LOCKED);
        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .isEqualTo(UsageStatus.OCCUPYING);
        assertThat(taskStatus(lock.usageNo())).isEqualTo(0);
    }

    @Test
    @Timeout(120)
    @DisplayName("T4【竞态①】：窗口内核销先到 → 核销成功；扫描后到 no-op（状态守卫 WHERE status=0）")
    void confirmBeforeScanWins() {
        long[] ids = freshCoupon("cf");
        LockResult lock = lock(ids[0], ids[1], "cf");
        var confirmed = tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), 1000L));

        couponReleaseService.scanOnce(0, 1);

        assertThat(confirmed.couponStatus()).isEqualTo("已核销");
        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USED);
        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .as("订单说过话了：占用单已核销，扫描收场影响 0 行").isEqualTo(UsageStatus.CONFIRMED);
        assertThat(lockTimeoutEventCount(ids[0])).as("不产生超时事件").isEqualTo(0);
    }

    @Test
    @Timeout(120)
    @DisplayName("T5【竞态②·D4】：扫描先收场，迟到核销 → 4006 明确拒绝、无副作用")
    void lateConfirmRejectedWith4006() {
        long[] ids = freshCoupon("late-cf");
        LockResult lock = lock(ids[0], ids[1], "late-cf");
        dueNow(lock.usageNo());
        couponReleaseService.scanOnce(0, 1);

        assertThatThrownBy(() -> tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), 1000L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .as("迟到核销拿到可编程错误码（订单侧按无券价处理），而非笼统冲突")
                .isEqualTo(ErrorCode.USAGE_TIMEOUT_CLOSED);

        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .as("券保持回池态未被迟到核销改动").isEqualTo(CouponStatus.USABLE);
        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .isEqualTo(UsageStatus.TIMEOUT_CLOSED);
    }

    @Test
    @Timeout(120)
    @DisplayName("T6【L15 断言翻转】：收场后迟到释放 → 幂等成功+券真实状态（不再硬编码'待使用'）")
    void lateReleaseIdempotentWithRealStatus() {
        long[] ids = freshCoupon("late-rl");
        LockResult lock = lock(ids[0], ids[1], "late-rl");
        dueNow(lock.usageNo());
        couponReleaseService.scanOnce(0, 1);

        ReleaseResult r = tradeAppService.release(new ReleaseRequest(lock.usageNo(), "订单取消消息迟到"));

        assertThat(r.duplicated()).as("取消意图已达成，当幂等成功").isTrue();
        assertThat(r.couponStatus()).as("读券真实状态：未过期=待使用").isEqualTo("待使用");

        // 变体：收场时券已过期 → 释放返回"已过期"（旧实现硬编码"待使用"在此说谎）
        long[] ids2 = freshCoupon("late-rl2");
        LockResult lock2 = lock(ids2[0], ids2[1], "late-rl2");
        expireCoupon(ids2[0]);
        dueNow(lock2.usageNo());
        couponReleaseService.scanOnce(0, 1);

        ReleaseResult r2 = tradeAppService.release(new ReleaseRequest(lock2.usageNo(), "迟到取消"));

        assertThat(r2.couponStatus()).as("L15：返回真实状态'已过期'，不再硬编码").isEqualTo("已过期");
    }

    @Test
    @Timeout(120)
    @DisplayName("T7：重复扫描幂等（任务已终结，二次扫描不产生重复事件/流转）")
    void rescanIsIdempotent() {
        long[] ids = freshCoupon("idem");
        LockResult lock = lock(ids[0], ids[1], "idem");
        dueNow(lock.usageNo());
        couponReleaseService.scanOnce(0, 1);
        couponReleaseService.scanOnce(0, 1);   // 重跑

        assertThat(lockTimeoutEventCount(ids[0])).as("无重复事件").isEqualTo(1);
        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .isEqualTo(UsageStatus.TIMEOUT_CLOSED);
        assertThat(taskStatus(lock.usageNo())).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("T8：失败=退避推进 retry_count、满 8 次死信(3)——不设'失败'状态")
    void backoffAndDeadLetter() {
        long[] ids = freshCoupon("backoff");
        LockResult lock = lock(ids[0], ids[1], "backoff");

        releaseTaskGateway.backoff(lock.usageNo());   // 第 1 次退避

        assertThat(taskStatus(lock.usageNo())).as("仍待处理（单值）").isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT retry_count FROM coupon_release_task WHERE usage_no = ?",
                Integer.class, lock.usageNo())).isEqualTo(1);

        jdbc.update("UPDATE coupon_release_task SET retry_count = 7 WHERE usage_no = ?", lock.usageNo());
        releaseTaskGateway.backoff(lock.usageNo());   // 第 8 次

        assertThat(taskStatus(lock.usageNo())).as("重试超限置死信等人工").isEqualTo(3);
    }

    @Test
    @Timeout(120)
    @DisplayName("T9【F1 复现】：券已终态但占用单仍占用中（半收场残局）→ expire 扫描补刀关占用单")
    void expireScannerBackfillsHalfClosedUsage() {
        long[] ids = freshCoupon("f1");
        LockResult lock = lock(ids[0], ids[1], "f1");
        // 模拟 F1 崩溃窗口：两条 UPDATE 之间进程死掉——券已被收场为已过期，占用单停在占用中，
        // 过期任务仍待处理（finish 还没来得及执行）
        LocalDateTime past = LocalDateTime.now().minusDays(2);
        jdbc.update("UPDATE user_coupon SET status = 3, valid_end = ? WHERE id = ?", past, ids[0]);
        jdbc.update("UPDATE coupon_expire_task SET next_scan_time = ? WHERE coupon_id = ?", past, ids[0]);

        couponExpireService.scanOnce(0, 1);

        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .as("F1 补刀：占用单被幂等补关为过期关闭，不再永久悬挂").isEqualTo(UsageStatus.EXPIRED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM coupon_expire_task WHERE coupon_id = ?", Integer.class, ids[0]))
                .isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("T10【F2 复现】：合同终结后重放锁券 → 不谎报'已锁定'；新订单号可重新锁定（券已回池）")
    void replayedLockAfterTerminalUsageIsRejected() {
        long[] ids = freshCoupon("f2");
        LockResult lock = lock(ids[0], ids[1], "f2");
        dueNow(lock.usageNo());
        couponReleaseService.scanOnce(0, 1);

        // 用与首次完全相同的订单号重放（usageNo 相同 → 命中幂等快检分支）
        String sameOrderNo = lock.usageNo().substring(0, lock.usageNo().lastIndexOf(':'));
        assertThatThrownBy(() -> tradeAppService.lock(new LockRequest(sameOrderNo, ids[1], ids[0])))
                .isInstanceOf(BizException.class)
                .as("F2：终态合同按状态冲突分类，提示换新订单号")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        // 新订单号锁定同一张券 → 成功（收场后券回池，可服务新合同）
        LockResult relock = lock(ids[0], ids[1], "f2-relock");
        assertThat(relock.duplicated()).isFalse();
        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.LOCKED);
    }

    @Test
    @Timeout(120)
    @DisplayName("T11【评审#1回归】：死信合同被审计复活并最终收场（INSERT IGNORE→ON DUPLICATE 修复）")
    void deadLetterTaskRevivedByAuditAndClosed() {
        long[] ids = freshCoupon("dl");
        LockResult lock = lock(ids[0], ids[1], "dl");
        // 制造死信：8 次退避全败的形态（占用单仍占用中——死信合同的真实处境）
        jdbc.update("UPDATE coupon_release_task SET status = 3, retry_count = 8, "
                + "next_scan_time = NOW() - INTERVAL 1 DAY WHERE usage_no = ?", lock.usageNo());
        // 审计的判定条件：占用中且锁龄超"窗口+10分钟"（窗口=0）→ 把 occupy_time 推老
        jdbc.update("UPDATE coupon_usage SET occupy_time = NOW() - INTERVAL 1 HOUR WHERE usage_no = ?",
                lock.usageNo());

        couponReleaseService.dailyAudit();   // 审计发现超龄合同 → 应复活死信（而非被 IGNORE 吞掉）

        assertThat(taskStatus(lock.usageNo())).as("死信被复活为待处理（旧实现此处仍是3）").isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT retry_count FROM coupon_release_task WHERE usage_no = ?",
                Integer.class, lock.usageNo())).as("重试清零重新给足8次").isEqualTo(0);

        couponReleaseService.scanOnce(0, 1);   // 复活后下一轮扫描正常收走

        assertThat(couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow().getStatus())
                .as("复活→收场全链路走通，合同不再永久悬挂").isEqualTo(UsageStatus.TIMEOUT_CLOSED);
        assertThat(userCouponRepository.findById(ids[0]).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USABLE);
    }
}
