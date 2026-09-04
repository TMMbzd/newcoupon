package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.repository.UserCouponRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 编排层全分支测试：幂等快查（同模板/换模板）、事务成功、DuplicateKey 四种分类。
 * 事务服务整被 mock——编排层的职责是"翻译冲突"，不依赖真事务。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("领券编排层")
class ReceiveAppServiceTest {

    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private CouponIssueTxService issueTxService;

    @InjectMocks
    private ReceiveAppService service;

    private static UserCoupon coupon(long templateId) {
        return UserCoupon.reconstitute(5L, 42L, templateId, "T1",
                1000L, 10000L, null,
                LocalDateTime.of(2026, 9, 12, 23, 59, 59), CouponStatus.USABLE,
                LocalDateTime.of(2026, 9, 5, 10, 0), "req-1");
    }

    // ---------- 幂等快查路径 ----------

    @Test
    @DisplayName("快查命中同模板 → duplicated=true 直接返回，不进事务")
    void fastPathSameTemplate() {
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L)));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();
        assertThat(r.couponId()).isEqualTo(5L);
        verify(issueTxService, never()).issue(any(), any());
    }

    @Test
    @DisplayName("快查命中但换模板（复用陈旧 requestId）→ REQUEST_DUPLICATED 拒绝，绝不当成功")
    void fastPathDifferentTemplateRejected() {
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L)));       // 已领的是模板1

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 2L)))  // 来领模板2
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
    }

    // ---------- 事务成功路径 ----------

    @Test
    @DisplayName("快查未命中 → 进事务领取成功，duplicated=false")
    void issueSuccess() {
        when(userCouponRepository.findByIdempotentKey(42L, "req-1")).thenReturn(Optional.empty());
        when(issueTxService.issue(any(), any())).thenReturn(coupon(1L));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isFalse();
        assertThat(r.couponId()).isEqualTo(5L);
    }

    // ---------- DuplicateKey 冲突分类（回滚后重查） ----------

    @Test
    @DisplayName("撞唯一索引→重查幂等键命中同模板 → duplicated=true（并发重放场景）")
    void dupKeyClassifiedAsIdempotent() {
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.empty())                    // 快查时未命中（并发窗口）
                .thenReturn(Optional.of(coupon(1L)));            // 回滚后重查命中（对手方已提交）
        when(issueTxService.issue(any(), any()))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_idem'"));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();
        assertThat(r.couponId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("撞唯一索引→重查幂等键命中但换模板 → REQUEST_DUPLICATED")
    void dupKeyClassifiedAsIdempotentConflict() {
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(coupon(1L)));
        when(issueTxService.issue(any(), any()))
                .thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 2L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
    }

    @Test
    @DisplayName("撞唯一索引→幂等键未命中+用户模板命中 → USER_LIMIT_EXCEEDED（并发超领场景）")
    void dupKeyClassifiedAsUserLimit() {
        when(userCouponRepository.findByIdempotentKey(42L, "req-2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(userCouponRepository.findByUserAndTemplate(42L, 1L))
                .thenReturn(Optional.of(coupon(1L)));            // 已领过这张券（别的 requestId）
        when(issueTxService.issue(any(), any()))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_user_template'"));

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-2", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("撞唯一索引但两路重查都未命中 → 系统异常兜底（理论不可达，绝不静默）")
    void dupKeyNoMatchFallsToSystemError() {
        when(userCouponRepository.findByIdempotentKey(42L, "req-3"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(userCouponRepository.findByUserAndTemplate(42L, 1L)).thenReturn(Optional.empty());
        when(issueTxService.issue(any(), any())).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-3", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SYSTEM_ERROR);
    }
}
