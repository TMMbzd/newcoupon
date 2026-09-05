package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.redis.RedisStockGateway;
import com.newcoupon.infrastructure.redis.RedisStockGateway.DeductResult;
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
 * 领券编排层（阶段 1：Redis Lua 闸门）全分支测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("领券编排层·Redis闸门")
class ReceiveAppServiceTest {

    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private CouponIssueTxService issueTxService;
    @Mock
    private RedisStockGateway stockGateway;
    @Mock
    private StockReconcileService reconcileService;

    @InjectMocks
    private ReceiveAppService service;

    private static CouponTemplate template(int perUserLimit) {
        return CouponTemplate.reconstitute(1L,
                CouponTemplate.Creation.builder()
                        .templateCode("T1").activityId(1L).name("满100减10")
                        .couponType(CouponType.FULL_REDUCTION)
                        .faceValue(1000L).threshold(10000L).totalCount(100L)
                        .perUserLimit(perUserLimit)
                        .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                10L, ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }

    private static UserCoupon coupon(long templateId, int seq) {
        return UserCoupon.reconstitute(5L, 42L, templateId, "T1", 1000L, 10000L, null,
                LocalDateTime.of(2026, 9, 14, 23, 59, 59), CouponStatus.USABLE,
                LocalDateTime.now(), "req-1", seq);
    }

    private void givenTemplateAndGate(int perUserLimit, DeductResult gate) {
        when(templateRepository.findById(anyLong())).thenReturn(Optional.of(template(perUserLimit)));
        when(stockGateway.deduct(any())).thenReturn(gate);
    }

    // ---------- 快查与闸门前校验 ----------

    @Test
    @DisplayName("Redis幂等命中(-8)同模板 → DB确认 → duplicated=true，不进事务")
    void gateIdemHitSameTemplate() {
        givenTemplateAndGate(1, new DeductResult(-8, 0));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();
        verifyNoInteractions(issueTxService);
    }

    @Test
    @DisplayName("Redis幂等命中(-8)但换模板 → DB确认后 1001 拒绝（requestId绑定领取意图）")
    void gateIdemHitDifferentTemplateRejected() {
        givenTemplateAndGate(1, new DeductResult(-8, 0));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 2L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
    }

    @Test
    @DisplayName("入口不再打DB快查：首次领取路径零前置DB幂等查询（评审修正的回归）")
    void noUpfrontDbIdempotentSelect() {
        givenTemplateAndGate(1, new DeductResult(-3, 0));   // 直接售罄
        when(userCouponRepository.findByIdempotentKey(42L, "req-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .isInstanceOf(BizException.class);
        // 闸门拒绝后的陈旧重放检查只查一次；若入口还有快查，这里会是两次
        verify(userCouponRepository, org.mockito.Mockito.times(1)).findByIdempotentKey(42L, "req-1");
    }

    @Test
    @DisplayName("模板不存在 → 2001（闸门前拦截）")
    void templateMissing() {
        when(templateRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND);
    }

    // ---------- 闸门拒绝分支 ----------

    @Test
    @DisplayName("闸门-3售罄（无陈旧重放）→ 3001")
    void gateSoldOut() {
        givenTemplateAndGate(1, new DeductResult(-3, 0));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_OUT);
    }

    @Test
    @DisplayName("闸门-2超领，但DB幂等命中 → 陈旧重放（idem键TTL过期）→ duplicated=true")
    void gateLimitButStaleReplay() {
        givenTemplateAndGate(1, new DeductResult(-2, 0));
        // 重排序后：入口无快查，闸门拒绝后的"陈旧重放检查"是唯一一次DB查询，直接命中
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();
    }

    @Test
    @DisplayName("闸门-8但DB未查到（并发在途：赢家未提交）→ 1001 处理中提示")
    void gateIdemHitButInFlight() {
        givenTemplateAndGate(1, new DeductResult(-8, 0));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        verifyNoInteractions(issueTxService);
    }

    @Test
    @DisplayName("闸门-7未初始化 → 懒重建后重试成功")
    void gateUninitializedRebuildsAndRetries() {
        when(templateRepository.findById(1L)).thenReturn(Optional.of(template(1)));
        when(stockGateway.deduct(any()))
                .thenReturn(new DeductResult(-7, 0))
                .thenReturn(new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), eq(1L), any())).thenReturn(coupon(1L, 1));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isFalse();
        verify(reconcileService).rebuildStock(1L);
    }

    // ---------- 事务成功与失败补偿 ----------

    @Test
    @DisplayName("闸门过+事务成功 → duplicated=false，无补偿")
    void happyPathNoCompensation() {
        givenTemplateAndGate(3, new DeductResult(0, 2));
        when(issueTxService.issue(any(), any(), eq(2L), any())).thenReturn(coupon(1L, 2));

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isFalse();
        verify(stockGateway, never()).compensate(any());
    }

    @Test
    @DisplayName("事务DuplicateKey → 补偿被调用 + 分类为幂等成功（uk_idem）")
    void duplicateKeyCompensatesAndClassifies() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), any()))
                .thenThrow(new DuplicateKeyException("uk_idem"));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.of(coupon(1L, 1)));   // 分类时的唯一一次DB查询直接命中

        ReceiveResult r = service.receive(new ReceiveRequest("req-1", 42L, 1L));

        assertThat(r.duplicated()).isTrue();
        verify(stockGateway).compensate(any());   // Redis 扣掉的必须还回去
    }

    @Test
    @DisplayName("事务DuplicateKey 且非幂等键 → 3002（Redis计数丢失，DB序号索引兜底）")
    void duplicateKeySeqConflictIsUserLimit() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), any()))
                .thenThrow(new DuplicateKeyException("uk_user_seq"));
        when(userCouponRepository.findByIdempotentKey(42L, "req-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_LIMIT_EXCEEDED);
        verify(stockGateway).compensate(any());
    }

    @Test
    @DisplayName("事务StockDeductRejected → 补偿 + 新快照分类为售罄")
    void stockRejectCompensatesAndClassifies() {
        givenTemplateAndGate(1, new DeductResult(0, 1));
        when(issueTxService.issue(any(), any(), anyLong(), any()))
                .thenThrow(new StockDeductRejectedException(1L));
        // 事务外分类重读：issued(100) >= total(100) → 售罄
        when(templateRepository.findById(anyLong())).thenReturn(Optional.of(
                CouponTemplate.reconstitute(1L,
                        CouponTemplate.Creation.builder()
                                .templateCode("T1").activityId(1L).name("满100减10")
                                .couponType(CouponType.FULL_REDUCTION)
                                .faceValue(1000L).threshold(10000L).totalCount(100L).perUserLimit(1)
                                .validType(ValidType.AFTER_RECEIVE).validDays(7).build(),
                        100L, ApproveStatus.APPROVED, PublishStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.receive(new ReceiveRequest("req-1", 42L, 1L)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_OUT);
        verify(stockGateway).compensate(any());
    }
}
