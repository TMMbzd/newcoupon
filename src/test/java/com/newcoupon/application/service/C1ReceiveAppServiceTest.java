package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.ApproveStatus;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.CouponType;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.coupon.UserCoupon;
import com.newcoupon.domain.coupon.ValidType;
import com.newcoupon.domain.repository.TemplateRepository;
import com.newcoupon.domain.repository.UserCouponRepository;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import com.newcoupon.infrastructure.resilience.DependencyCircuitBreakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * P0-5：同步领取的热路径不再对 user_coupon 做 COUNT。
 *
 * <p>每人总限由 MAX(seq)+1 判断；每人日限/模板日限在 issueC1 的事务内用 V27 条件计数。
 * 这里只钉“限领查询不发生”，真实计数行为由 C1StockIntegrationTest 的两个日限用例覆盖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("C1 同步领取·热路径去 COUNT")
class C1ReceiveAppServiceTest {

    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private CachedTemplateReader cachedTemplateReader;
    @Mock
    private CouponIssueTxService issueTxService;
    @Mock
    private CouponStockShardMapper shardMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private DependencyCircuitBreakers breakers;

    private C1ReceiveAppService service;

    @BeforeEach
    void setUp() {
        service = new C1ReceiveAppService(userCouponRepository, templateRepository,
                cachedTemplateReader, issueTxService, shardMapper, redis, breakers);
    }

    @Test
    @DisplayName("成功领取只走 MAX(seq) 与事务，不调用两个用户 COUNT")
    void successDoesNotCountUserCoupons() {
        long templateId = 1L;
        long userId = 42L;
        CouponTemplate template = template(templateId);
        ReceiveRequest req = new ReceiveRequest("req-no-count", userId, templateId);

        when(breakers.mysql()).thenReturn(CircuitBreaker.ofDefaults("c1-no-count"));
        when(userCouponRepository.findByIdempotentKey(eq(userId), anyString(), eq("req-no-count")))
                .thenReturn(Optional.empty());
        when(userCouponRepository.maxSeqByUserAndTemplate(userId, templateId)).thenReturn(0L);
        when(redis.hasKey(anyString())).thenReturn(false);
        UserCoupon coupon = UserCoupon.issue(template, userId, "req-no-count", 1, LocalDateTime.now());
        when(issueTxService.issueC1(any(), eq(template), eq(1), eq(1), anyInt(),
                any(LocalDateTime.class), any(LocalDate.class))).thenReturn(coupon);

        ReceiveResult result = service.receive(req, template);

        assertThat(result.duplicated()).isFalse();
        verify(userCouponRepository, never()).countByUserAndTemplate(anyLong(), anyLong());
        verify(userCouponRepository, never()).countTodayByUserAndTemplate(
                anyLong(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("并发窗口：日限事务拒绝时回查同 requestId，已发券返回 duplicated")
    void limitRejectProbesIdempotentCoupon() {
        long templateId = 1L;
        long userId = 42L;
        CouponTemplate template = template(templateId);
        String requestId = "req-concurrent-duplicate";
        ReceiveRequest req = new ReceiveRequest(requestId, userId, templateId);
        UserCoupon existing = UserCoupon.issue(template, userId, requestId, 1, LocalDateTime.now());

        when(breakers.mysql()).thenReturn(CircuitBreaker.ofDefaults("c1-concurrent"));
        when(userCouponRepository.findByIdempotentKey(eq(userId), anyString(), eq(requestId)))
                .thenReturn(Optional.empty())          // 第一次快查未命中（对手尚未提交）
                .thenReturn(Optional.of(existing));    // 日限事务拒绝后回查命中
        when(userCouponRepository.maxSeqByUserAndTemplate(userId, templateId)).thenReturn(0L);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(issueTxService.issueC1(any(), eq(template), eq(1), eq(1), anyInt(),
                any(LocalDateTime.class), any(LocalDate.class)))
                .thenThrow(new BizException(ErrorCode.USER_LIMIT_EXCEEDED, "已达每日限领上限"));

        ReceiveResult result = service.receive(req, template);

        assertThat(result.duplicated()).isTrue();
        verify(userCouponRepository, never()).countByUserAndTemplate(anyLong(), anyLong());
        verify(userCouponRepository, never()).countTodayByUserAndTemplate(
                anyLong(), anyLong(), any(LocalDateTime.class));
    }

    private static CouponTemplate template(long id) {
        CouponTemplate.Creation creation = CouponTemplate.Creation.builder()
                .templateCode("C1-NO-COUNT")
                .activityId(1L)
                .name("热路径去 COUNT 测试券")
                .couponType(CouponType.NO_THRESHOLD)
                .faceValue(100L)
                .threshold(0L)
                .totalCount(10L)
                .perUserLimit(5)
                .dailyLimit(1L)
                .perUserDailyLimit(1)
                .validType(ValidType.FIXED)
                .validStart(LocalDateTime.now().minusDays(1))
                .validEnd(LocalDateTime.now().plusDays(1))
                .build();
        return CouponTemplate.reconstitute(id, creation, 0L,
                ApproveStatus.APPROVED, PublishStatus.PUBLISHED);
    }
}
