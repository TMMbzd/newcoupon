package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import com.newcoupon.domain.repository.TemplateRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 应用服务单测：仓储用 Mockito 假实现——**不需要数据库**。
 * 这是端口-适配器架构的直接红利：领域定义了 TemplateRepository 接口，
 * 测试里 mock 它，用例逻辑（编排、策略、CAS 冲突分支）就能全部覆盖，毫秒级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("模板应用服务")
class TemplateAppServiceTest {

    @Mock
    private TemplateRepository repository;

    @InjectMocks
    private TemplateAppService service;

    private static TemplateCreateRequest validRequest() {
        return new TemplateCreateRequest(
                "满100减10测试券", 1001L, 1, 1000L, null, 10000L, 10000L, 1,
                1, LocalDateTime.of(2026, 9, 4, 0, 0),
                LocalDateTime.of(2026, 9, 30, 23, 59, 59), null);
    }

    @Test
    @DisplayName("建券：阶段0拒绝 perUserLimit != 1（ADR-0004 策略），且不触碰仓储")
    void createRejectsNonStage0Limit() {
        TemplateCreateRequest req = validRequest();
        TemplateCreateRequest bad = new TemplateCreateRequest(
                req.name(), req.activityId(), req.couponType(), req.faceValue(), req.discountCap(),
                req.threshold(), req.totalCount(), 5,   // ← 限领 5 张
                req.validType(), req.validStart(), req.validEnd(), req.validDays());

        assertThatThrownBy(() -> service.create(bad))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("限领 1 张");

        verify(repository, never()).save(any());   // 策略校验在仓储之前
    }

    @Test
    @DisplayName("建券：合法请求 → 落库成功，返回 id 与生成的业务编码")
    void createSavesAndReturns() {
        when(repository.save(any())).thenAnswer(inv -> {
            CouponTemplate t = inv.getArgument(0);
            return t.withId(77L);
        });

        TemplateAppService.CreatedTemplate created = service.create(validRequest());

        assertThat(created.id()).isEqualTo(77L);
        // 编码 = "T" + yyyyMMddHHmmss(14位) + 3位随机 = 18 字符
        assertThat(created.templateCode()).startsWith("T").hasSize(18);
    }

    @Test
    @DisplayName("发布：模板不存在 → TEMPLATE_NOT_FOUND")
    void publishRejectsMissing() {
        when(repository.findById(anyLong())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.publish(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEMPLATE_NOT_FOUND);
    }

    @Test
    @DisplayName("发布：CAS 失败（并发变更）→ STATE_CONFLICT，错误信息引导重试")
    void publishSurfacesCasConflict() {
        when(repository.findById(anyLong())).thenReturn(Optional.of(
                CouponTemplate.reconstitute(1L,
                        CouponTemplate.Creation.builder()
                                .templateCode("T1").activityId(1L).name("n")
                                .couponType(com.newcoupon.domain.coupon.CouponType.NO_THRESHOLD)
                                .faceValue(500L).threshold(0L).totalCount(10L).perUserLimit(1)
                                .validType(com.newcoupon.domain.coupon.ValidType.AFTER_RECEIVE)
                                .validDays(7).build(),
                        0L,
                        com.newcoupon.domain.coupon.ApproveStatus.APPROVED,
                        PublishStatus.UNPUBLISHED)));
        when(repository.casUpdatePublishStatus(1L, PublishStatus.UNPUBLISHED, PublishStatus.PUBLISHED))
                .thenReturn(false);   // ← 模拟并发：条件更新影响 0 行

        assertThatThrownBy(() -> service.publish(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }
}
