package com.newcoupon.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.open.api.dto.CalculateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 核销 DTO 的嵌套校验（2026-10-18 对抗审查发现：ConfirmRequest.items 缺 @Valid/@Size，
 * 负数单价和超大订单行会绕过入口校验直接进计算引擎）。
 *
 * <p>纯 JUnit 单测：不起 Spring、不连中间件，验证注解契约本身。
 */
class TradeDtosValidationTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("核销 items 的负数单价被入口校验拒绝（@Valid 级联到 OrderItem）")
    void confirmRejectsNegativePrice() {
        ConfirmRequest req = new ConfirmRequest("ORD-1:1", null,
                List.of(new CalculateRequest.OrderItem("SKU", "CAT", -1L, 1)));

        assertThat(VALIDATOR.validate(req))
                .as("负数单价必须被 @Positive 拦住，不能进计算引擎")
                .isNotEmpty();
    }

    @Test
    @DisplayName("核销 items 超过 100 行被入口校验拒绝（@Size）")
    void confirmRejectsTooManyLines() {
        List<CalculateRequest.OrderItem> items = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            items.add(new CalculateRequest.OrderItem("SKU" + i, "CAT", 100L, 1));
        }
        ConfirmRequest req = new ConfirmRequest("ORD-1:1", null, items);

        assertThat(VALIDATOR.validate(req))
                .as("核销商品行上限 100，与试算一致")
                .isNotEmpty();
    }

    @Test
    @DisplayName("合法核销 items 通过校验")
    void validConfirmPasses() {
        ConfirmRequest req = new ConfirmRequest("ORD-1:1", null,
                List.of(new CalculateRequest.OrderItem("SKU", "CAT", 100L, 1, "L1")));

        assertThat(VALIDATOR.validate(req)).isEmpty();
    }
}
