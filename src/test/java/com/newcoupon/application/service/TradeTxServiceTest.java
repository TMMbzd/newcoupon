package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.ReleaseRequest;
import com.newcoupon.common.BizException;
import com.newcoupon.domain.coupon.CouponEvent;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.CouponReleaseTaskGateway;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("用券三段事务服务")
class TradeTxServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 12, 0);

    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private CouponUsageRepository couponUsageRepository;
    @Mock
    private CouponEventRepository couponEventRepository;
    @Mock
    private CouponReleaseTaskGateway releaseTaskGateway;

    private TradeTxService service;

    @BeforeEach
    void constructService() {
        // L5 后构造器多了锁定超时网关与窗口参数（long 基本类型 Mockito 注入不了，
        // @InjectMocks 在扩展的 beforeEach 里先炸）——去注解、手工组装
        // 三个退款 Mapper 传 null：本类是纯状态机单测，退款路径由集成测试覆盖
        service = new TradeTxService(userCouponRepository, couponUsageRepository,
                couponEventRepository, null, null, null, null, null, null, null,
                releaseTaskGateway, 30L);
    }

    private static UserCoupon coupon(CouponStatus status) {
        return UserCoupon.reconstitute(5L, 42L, 1L, "T1", 1000L, 10000L, null,
                LocalDateTime.of(2026, 9, 12, 23, 59, 59), status, NOW, "req-1", 1);
    }

    private static CouponUsage usage(UsageStatus status, Long amt) {
        return CouponUsage.reconstitute(9L, "ORD-1:5", 42L, 5L, "ORD-1",
                status, amt, NOW, null, null);
    }

    @Test
    @DisplayName("锁券：状态迁移+建占用单+记锁定事件，返回 usageNo=订单号:券id")
    void lockHappyPath() {
        when(userCouponRepository.findByIdForUser(5L, 42L)).thenReturn(Optional.of(coupon(CouponStatus.USABLE)));
        when(userCouponRepository.casLock(5L, 42L)).thenReturn(true);
        when(couponUsageRepository.save(any())).thenAnswer(inv -> inv.<CouponUsage>getArgument(0).withId(9L));

        LockResult r = service.lock(new LockRequest("ORD-1", 42L, 5L), NOW);

        assertThat(r.usageNo()).isEqualTo("ORD-1:5");
        assertThat(r.duplicated()).isFalse();
        verify(couponEventRepository).append(any(CouponEvent.class));   // 锁定事件必写
    }

    @Test
    @DisplayName("锁券：券不属于该用户 → 拒绝（防拿他人券下单），CAS 不发生")
    void lockRejectsForeignCoupon() {
        // 券的 userId=42（coupon() 工厂固定 42），请求用 99——路由查询带 99 落空
        // → 直接"券不存在"（这也正是路由键防越权的副产物：查别人+带错号=查不到）
        when(userCouponRepository.findByIdForUser(5L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lock(new LockRequest("ORD-1", 99L, 5L), NOW))   // 用户99
                .isInstanceOf(com.newcoupon.common.BizException.class);   // USER_COUPON_NOT_FOUND
        verify(userCouponRepository, never()).casLock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("核销幂等：占用单已核销 → 直接当成功返回（不产生第二次核销事件）")
    void confirmIdempotent() {
        when(couponUsageRepository.findByUsageNo("ORD-1:5"))
                .thenReturn(Optional.of(usage(UsageStatus.CONFIRMED, 1000L)));

        var r = service.confirm(new ConfirmRequest("ORD-1:5", null), NOW, null);

        assertThat(r.duplicated()).isTrue();
        assertThat(r.discountAmt()).isEqualTo(1000L);
        verify(couponEventRepository, never()).append(any());
    }

    @Test
    @DisplayName("释放幂等：占用单已释放 → 当成功返回（取消消息重放安全）")
    void releaseIdempotent() {
        when(couponUsageRepository.findByUsageNo("ORD-1:5"))
                .thenReturn(Optional.of(usage(UsageStatus.RELEASED, null)));

        var r = service.release(new ReleaseRequest("ORD-1:5", "取消"), NOW);

        assertThat(r.duplicated()).isTrue();
        verify(couponEventRepository, never()).append(any());
    }

    @Test
    @DisplayName("释放资损红线：已核销的占用单 → 拒绝释放")
    void releaseRejectedAfterConfirm() {
        when(couponUsageRepository.findByUsageNo("ORD-1:5"))
                .thenReturn(Optional.of(usage(UsageStatus.CONFIRMED, 1000L)));

        assertThatThrownBy(() -> service.release(new ReleaseRequest("ORD-1:5", "取消"), NOW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已核销不可释放");
    }

    @Test
    @DisplayName("已退款/部分退款的占用单收到释放 → 4004 明确拒绝（售后中不是“取消”）")
    void releaseRejectedAfterRefund() {
        when(couponUsageRepository.findByUsageNo("ORD-1:5"))
                .thenReturn(Optional.of(usage(UsageStatus.REFUNDED, 1000L)));
        assertThatThrownBy(() -> service.release(new ReleaseRequest("ORD-1:5", "取消"), NOW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可释放");

        when(couponUsageRepository.findByUsageNo("ORD-1:5"))
                .thenReturn(Optional.of(usage(UsageStatus.PARTIALLY_REFUNDED, 1000L)));
        assertThatThrownBy(() -> service.release(new ReleaseRequest("ORD-1:5", "取消"), NOW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可释放");
    }

    // ════ 加锁顺序不变量（2026-10-15，死锁对修复）════

    @Test
    @DisplayName("超时收场加锁顺序：占用单 CAS 必须先于券 CAS——四条链路统一顺序，消除 AB-BA 死锁")
    void timeoutCloseLocksUsageBeforeCoupon() {
        when(couponUsageRepository.findByUsageNo("ORD-1:5"))
                .thenReturn(Optional.of(usage(UsageStatus.OCCUPYING, null)));
        when(userCouponRepository.findById(5L)).thenReturn(Optional.of(coupon(CouponStatus.LOCKED)));
        when(couponUsageRepository.casTimeoutByUsageNo("ORD-1:5")).thenReturn(true);
        when(userCouponRepository.casTimeoutClose(5L, 42L)).thenReturn(true);

        assertThat(service.timeoutClose("ORD-1:5", NOW)).isTrue();

        // 顺序钉死：先占用单、后券——与核销/释放/过期收场一致。顺序反了就是 AB-BA 死锁对
        InOrder inOrder = inOrder(couponUsageRepository, userCouponRepository);
        inOrder.verify(couponUsageRepository).casTimeoutByUsageNo("ORD-1:5");
        inOrder.verify(userCouponRepository).casTimeoutClose(5L, 42L);
    }

    @Test
    @DisplayName("超时收场第二刀砍空（券已被并发改动）→ 抛异常整体回滚，绝不提交\"单关了券没动\"的半截状态")
    void timeoutCloseCouponCasMissMustRollback() {
        when(couponUsageRepository.findByUsageNo("ORD-1:5"))
                .thenReturn(Optional.of(usage(UsageStatus.OCCUPYING, null)));
        when(userCouponRepository.findById(5L)).thenReturn(Optional.of(coupon(CouponStatus.LOCKED)));
        when(couponUsageRepository.casTimeoutByUsageNo("ORD-1:5")).thenReturn(true);
        when(userCouponRepository.casTimeoutClose(5L, 42L)).thenReturn(false);

        assertThatThrownBy(() -> service.timeoutClose("ORD-1:5", NOW))
                .isInstanceOf(TradeCasRejectedException.class);
        verify(couponEventRepository, never()).append(any());
    }

    @Test
    @DisplayName("已退款的占用单收到重复核销 → 4004 明确拒绝（防\"请刷新重试\"落在死状态上、调用方无限重试）")
    void confirmOnRefundedUsageGetsDefinitiveRejection() {
        when(couponUsageRepository.findByUsageNo("ORD-1:5"))
                .thenReturn(Optional.of(usage(UsageStatus.REFUNDED, 2000L)));

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest("ORD-1:5", 1000L), NOW, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已退款");
        verify(couponUsageRepository, never()).casConfirm(any(), anyLong(), any());
    }
}
