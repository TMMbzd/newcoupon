package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.repository.CouponEventRepository;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@DisplayName("领券事务服务（阶段1形态：DB双防线+落库+事件）")
class CouponIssueTxServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 7, 10, 0);

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private CouponEventRepository couponEventRepository;
    @Mock
    private com.newcoupon.infrastructure.persistence.CouponExpireTaskGateway expireTaskGateway;

    @InjectMocks
    private CouponIssueTxService service;

    private static CouponTemplate template() {
        return CouponTemplate.reconstitute(1L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("满100减10")
                        .couponType(CouponType.FULL_REDUCTION)
                        .faceValue(1000L).threshold(10000L).totalCount(100L).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                10L, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }

    private static ReceiveRequest req() {
        return new ReceiveRequest("req-1", 42L, 1L);
    }

    @Test
    @DisplayName("成功：DB扣账 → 落券(带seq) → 记事件")
    void successPath() {
        when(templateRepository.casDeductStock(1L)).thenReturn(1);
        when(userCouponRepository.save(any())).thenAnswer(
                inv -> inv.<UserCoupon>getArgument(0).withId(5L));

        UserCoupon result = service.issue(req(), template(), 1L, NOW);

        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getSeq()).isEqualTo(1);
        verify(couponEventRepository, times(1)).append(any());
    }

    @Test
    @DisplayName("DB扣账 0 行 → 抛信号（分类在编排层，事务内不重读——RR 快照教训）")
    void zeroRowThrowsSignal() {
        when(templateRepository.casDeductStock(1L)).thenReturn(0);

        assertThatThrownBy(() -> service.issue(req(), template(), 1L, NOW))
                .isInstanceOf(StockDeductRejectedException.class);
        verify(userCouponRepository, never()).save(any());
        verify(couponEventRepository, never()).append(any());
    }

    @Test
    @DisplayName("落券撞唯一索引（幂等/序号）→ 异常透传给编排层做补偿+分类")
    void duplicateKeyPropagates() {
        when(templateRepository.casDeductStock(1L)).thenReturn(1);
        when(userCouponRepository.save(any())).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.issue(req(), template(), 1L, NOW))
                .isInstanceOf(DuplicateKeyException.class);
        verify(couponEventRepository, never()).append(any());
        verify(templateRepository, times(1)).casDeductStock(anyLong());   // 扣账确实发生了（待补偿）
    }
}
