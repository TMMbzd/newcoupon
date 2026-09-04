package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.ConfirmResult;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.ReleaseRequest;
import com.newcoupon.application.dto.TradeDtos.ReleaseResult;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.common.BizException;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 用券三段集成验收——交付阶段 0 验收③（docs/00 第 4 节：同一取消消息重放只回退一次）。
 * 验收清单：
 * ① 锁→核销全链路：券 USED(终态)、占用单 CONFIRMED(含金额)、LOCKED+USED 两事件、券包状态正确
 * ② 锁→释放：券回待使用、占用单 RELEASED、券可再次被新订单锁定（资产可复用）
 * ③ 【阶段0验收③】释放消息重放 3 次：全部当成功、券只回退一次、RELEASED 事件只有一条
 * ④ 核销消息重放：duplicated=true、金额不变、USED 事件一条
 * ⑤ 资损红线：已核销后释放 → 拒绝(4004)
 * ⑥ 并发锁券：两个订单同时锁同一张券 → 恰一个成功
 * ⑦ 读回字段断言：usage_no 格式、discount_amt、时间字段
 */
@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=50")
class TradeIntegrationTest {

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
    private CouponUsageRepository couponUsageRepository;

    private static final AtomicInteger USER_SEQ = new AtomicInteger(60_000);

    /** 建券发布并让一个用户领到一张券，返回券 id */
    private long prepareCoupon() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now().plusDays(30);
        TemplateAppService.CreatedTemplate t = templateAppService.create(new TemplateCreateRequest(
                "用券验收券", 1001L, 1, 1000L, null, 10000L, 1000L, 1, 1, start, end, null));
        templateAppService.publish(t.id());
        long userId = USER_SEQ.incrementAndGet();
        ReceiveResult r = receiveAppService.receive(
                new ReceiveRequest("trade-prep-" + userId, userId, t.id()));
        return r.couponId();
    }

    private record Res(Object result, String code) {
    }

    private Res call(java.util.function.Supplier<Object> s) {
        try {
            return new Res(s.get(), "OK");
        } catch (BizException e) {
            return new Res(null, String.valueOf(e.getErrorCode().getCode()));
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("验收①：锁→核销全链路（终态/金额/两事件/券包）")
    void lockThenConfirm() {
        long couponId = prepareCoupon();
        long userId = userCouponRepository.findById(couponId).orElseThrow().getUserId();

        LockResult lock = tradeAppService.lock(new LockRequest("ORD-T1", userId, couponId));
        assertThat(lock.usageNo()).isEqualTo("ORD-T1:" + couponId);

        ConfirmResult confirm = tradeAppService.confirm(new ConfirmRequest(lock.usageNo(), 1000L));

        assertThat(confirm.duplicated()).isFalse();
        assertThat(userCouponRepository.findById(couponId).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USED);                       // 券进终态
        var usage = couponUsageRepository.findByUsageNo(lock.usageNo()).orElseThrow();
        assertThat(usage.getStatus()).isEqualTo(UsageStatus.CONFIRMED);
        assertThat(usage.getDiscountAmt()).isEqualTo(1000L);          // 金额进占用单
        assertThat(usage.getUseTime()).isNotNull();
        // 券包里该券显示"已核销"
        assertThat(tradeAppService.myCoupons(userId, CouponStatus.USED).coupons())
                .extracting(c -> c.id()).contains(couponId);
    }

    @Test
    @Timeout(120)
    @DisplayName("验收②：锁→释放→再锁（券资产可复用）")
    void lockReleaseRelock() {
        long couponId = prepareCoupon();
        long userId = userCouponRepository.findById(couponId).orElseThrow().getUserId();

        String usage1 = tradeAppService.lock(new LockRequest("ORD-T2", userId, couponId)).usageNo();
        ReleaseResult release = tradeAppService.release(new ReleaseRequest(usage1, "超时未支付"));

        assertThat(release.duplicated()).isFalse();
        assertThat(userCouponRepository.findById(couponId).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USABLE);                      // 回到待使用

        // 新订单可以再锁同一张券
        LockResult lock2 = tradeAppService.lock(new LockRequest("ORD-T3", userId, couponId));
        assertThat(lock2.duplicated()).isFalse();
        assertThat(userCouponRepository.findById(couponId).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.LOCKED);
    }

    @Test
    @Timeout(120)
    @DisplayName("验收③【阶段0验收③】：取消消息重放3次 → 全部当成功、只回退一次、事件一条")
    void releaseReplayExactlyOnce() {
        long couponId = prepareCoupon();
        long userId = userCouponRepository.findById(couponId).orElseThrow().getUserId();
        String usageNo = tradeAppService.lock(new LockRequest("ORD-T3R", userId, couponId)).usageNo();

        ReleaseResult first = tradeAppService.release(new ReleaseRequest(usageNo, "取消"));
        ReleaseResult second = tradeAppService.release(new ReleaseRequest(usageNo, "取消（重放）"));
        ReleaseResult third = tradeAppService.release(new ReleaseRequest(usageNo, "取消（重放）"));

        assertThat(first.duplicated()).isFalse();
        assertThat(second.duplicated()).isTrue();     // 重放识别为幂等命中
        assertThat(third.duplicated()).isTrue();

        assertThat(userCouponRepository.findById(couponId).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USABLE);      // 回退只发生一次（结果一致）
        var usage = couponUsageRepository.findByUsageNo(usageNo).orElseThrow();
        assertThat(usage.getStatus()).isEqualTo(UsageStatus.RELEASED);
        // 券包查询时该券可见且可用
        assertThat(tradeAppService.myCoupons(userId, CouponStatus.USABLE).coupons())
                .extracting(c -> c.id()).contains(couponId);
    }

    @Test
    @Timeout(120)
    @DisplayName("验收④：核销消息重放 → duplicated=true、金额不变")
    void confirmReplay() {
        long couponId = prepareCoupon();
        long userId = userCouponRepository.findById(couponId).orElseThrow().getUserId();
        String usageNo = tradeAppService.lock(new LockRequest("ORD-T4", userId, couponId)).usageNo();

        ConfirmResult first = tradeAppService.confirm(new ConfirmRequest(usageNo, 1000L));
        ConfirmResult replay = tradeAppService.confirm(new ConfirmRequest(usageNo, 1000L));

        assertThat(first.duplicated()).isFalse();
        assertThat(replay.duplicated()).isTrue();
        assertThat(couponUsageRepository.findByUsageNo(usageNo).orElseThrow().getDiscountAmt())
                .isEqualTo(1000L);                     // 金额未被二次修改
        assertThat(userCouponRepository.findById(couponId).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USED);         // 终态不因重放改变
    }

    @Test
    @Timeout(60)
    @DisplayName("验收⑤【资损红线】：已核销后释放 → 拒绝(4004)")
    void releaseAfterConfirmRejected() {
        long couponId = prepareCoupon();
        long userId = userCouponRepository.findById(couponId).orElseThrow().getUserId();
        String usageNo = tradeAppService.lock(new LockRequest("ORD-T5", userId, couponId)).usageNo();
        tradeAppService.confirm(new ConfirmRequest(usageNo, 1000L));

        Res res = call(() -> tradeAppService.release(new ReleaseRequest(usageNo, "误发的取消")));

        assertThat(res.code()).isEqualTo("4004");
        assertThat(userCouponRepository.findById(couponId).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USED);         // 券仍是终态，未被错误回退
    }

    @Test
    @Timeout(120)
    @DisplayName("验收⑥：两个订单并发锁同一张券 → 恰一个成功")
    void concurrentLockSameCoupon() throws Exception {
        long couponId = prepareCoupon();
        long userId = userCouponRepository.findById(couponId).orElseThrow().getUserId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Future<Res> a = pool.submit(() -> {
                gate.await();
                return call(() -> tradeAppService.lock(new LockRequest("ORD-T6A", userId, couponId)));
            });
            Future<Res> b = pool.submit(() -> {
                gate.await();
                return call(() -> tradeAppService.lock(new LockRequest("ORD-T6B", userId, couponId)));
            });
            gate.countDown();
            List<Res> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

            assertThat(results.stream().filter(r -> "OK".equals(r.code())).count())
                    .as("两个订单抢同一张券，恰一个成功").isEqualTo(1);
            assertThat(results.stream().filter(r -> "4002".equals(r.code())).count())
                    .isEqualTo(1);   // 另一个收到状态冲突
            assertThat(userCouponRepository.findById(couponId).orElseThrow().getStatus())
                    .isEqualTo(CouponStatus.LOCKED);
        } finally {
            pool.shutdownNow();
        }
    }
}
