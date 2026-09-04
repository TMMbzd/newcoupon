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
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
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
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("领券事务服务")
class CouponIssueTxServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 10, 0);

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private CouponEventRepository couponEventRepository;

    @InjectMocks
    private CouponIssueTxService service;

    private static CouponTemplate template(PublishStatus publish, long issued, long total) {
        return CouponTemplate.reconstitute(1L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("满100减10")
                        .couponType(CouponType.FULL_REDUCTION)
                        .faceValue(1000L).threshold(10000L).totalCount(total).perUserLimit(1)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                issued, ApproveStatus.APPROVED, publish);
    }

    private static ReceiveRequest req() {
        return new ReceiveRequest("req-1", 42L, 1L);
    }

    @Test
    @DisplayName("成功路径：扣1 → 落券 → 记事件，三步顺序执行")
    void successIssuesCouponAndEvent() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(template(PublishStatus.PUBLISHED, 0, 100)));
        when(templateRepository.casDeductStock(1L)).thenReturn(1);
        when(userCouponRepository.save(any())).thenAnswer(inv -> inv.<UserCoupon>getArgument(0).withId(5L));

        UserCoupon result = service.issue(req(), NOW);

        assertThat(result.getId()).isEqualTo(5L);
        verify(userCouponRepository, times(1)).save(any());
        verify(couponEventRepository, times(1)).append(any());   // 事件必写（事实源）
    }

    @Test
    @DisplayName("扣减0行且重读已售罄 → STOCK_OUT，不落券不记事件")
    void zeroRowSoldOut() {
        when(templateRepository.findById(1L))
                .thenReturn(Optional.of(template(PublishStatus.PUBLISHED, 99, 100)))
                .thenReturn(Optional.of(template(PublishStatus.PUBLISHED, 100, 100)));  // 重读：已满
        when(templateRepository.casDeductStock(1L)).thenReturn(0);

        assertThatThrownBy(() -> service.issue(req(), NOW))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_OUT);
        verify(userCouponRepository, never()).save(any());
        verify(couponEventRepository, never()).append(any());
    }

    @Test
    @DisplayName("扣减0行但库存有余 → 模板状态竞态（被下线/审批回退）→ TEMPLATE_NOT_RECEIVABLE")
    void zeroRowStatusRace() {
        when(templateRepository.findById(1L))
                .thenReturn(Optional.of(template(PublishStatus.PUBLISHED, 10, 100)))
                .thenReturn(Optional.of(template(PublishStatus.OFFLINE, 10, 100)));     // 重读：已下线
        when(templateRepository.casDeductStock(1L)).thenReturn(0);

        assertThatThrownBy(() -> service.issue(req(), NOW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("下线");
    }

    @Test
    @DisplayName("模板不存在 → TEMPLATE_NOT_FOUND")
    void templateMissing() {
        when(templateRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issue(req(), NOW))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND);
        verify(templateRepository, never()).casDeductStock(anyLong());
    }

    @Test
    @DisplayName("模板未发布 → assertReceivable 拒绝，扣减根本不发生")
    void unpublishedBlockedBeforeDeduct() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(template(PublishStatus.UNPUBLISHED, 0, 100)));

        assertThatThrownBy(() -> service.issue(req(), NOW))
                .hasMessageContaining("仅已发布可领取");
        verify(templateRepository, never()).casDeductStock(anyLong());   // 校验在扣减之前
    }
}
