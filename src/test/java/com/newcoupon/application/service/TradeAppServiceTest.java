package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponUsage;
import com.newcoupon.domain.coupon.UsageStatus;
import com.newcoupon.domain.repository.CouponUsageRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 锁券编排层分支——E2E 抓到的 Bug 的回归测试：
 * 同一订单重复锁券必须返回幂等成功（duplicated=true），而不是状态冲突。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用券编排层·锁券幂等")
class TradeAppServiceTest {

    @Mock
    private TradeTxService tradeTxService;
    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private CouponUsageRepository couponUsageRepository;

    // 手动构造：构造器新增了两个 @Value 基本类型参数（占比上限/速率阈值），
    // @InjectMocks 无法给基本类型注入值——按生产默认值 80/600 手工传入。
    // DiscountCalculatorService 本组用例不走核销路径，传 null。
    // 构造放 @BeforeEach（不能放字段初始化器）：@Mock 字段由 Mockito 在每个测试前注入，
    // 字段初始化器跑得更早，那时 mock 还是 null。
    private TradeAppService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new TradeAppService(
                tradeTxService, userCouponRepository, couponUsageRepository, null, 80, 600);
    }

    private static CouponUsage usage() {
        return CouponUsage.reconstitute(9L, "ORD-1:5", 42L, 5L, "ORD-1",
                UsageStatus.OCCUPYING, null, LocalDateTime.now(), null, null);
    }

    @Test
    @DisplayName("串行重放：快查命中占用单 → duplicated=true，不进事务")
    void serialRelayHitsFastPath() {
        when(couponUsageRepository.findByUsageNo("ORD-1:5")).thenReturn(Optional.of(usage()));

        LockResult r = service.lock(new LockRequest("ORD-1", 42L, 5L));

        assertThat(r.duplicated()).isTrue();
        assertThat(r.usageNo()).isEqualTo("ORD-1:5");
        verify(tradeTxService, never()).lock(any(), any());
    }

    @Test
    @DisplayName("并发窗口兜底：事务层内存快检抛状态冲突，但占用单是本单的 → 幂等成功")
    void concurrentWindowFallback() {
        when(couponUsageRepository.findByUsageNo("ORD-1:5"))
                .thenReturn(Optional.empty())               // 快查时未命中（并发）
                .thenReturn(Optional.of(usage()));          // 冲突后重查命中（对手已提交）
        when(tradeTxService.lock(any(), any()))
                .thenThrow(new BizException(ErrorCode.STATE_CONFLICT, "券当前状态=已锁定..."));

        LockResult r = service.lock(new LockRequest("ORD-1", 42L, 5L));

        assertThat(r.duplicated()).isTrue();
    }

    @Test
    @DisplayName("真冲突：券被其他订单锁定（占用单不是本单的）→ 状态冲突错误")
    void genuineConflictSurfaced() {
        when(couponUsageRepository.findByUsageNo("ORD-2:5"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(tradeTxService.lock(any(), any()))
                .thenThrow(new BizException(ErrorCode.STATE_CONFLICT, "券当前状态=已锁定..."));

        assertThatThrownBy(() -> service.lock(new LockRequest("ORD-2", 42L, 5L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }
}
