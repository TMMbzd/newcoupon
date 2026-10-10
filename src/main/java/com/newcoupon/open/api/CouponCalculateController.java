package com.newcoupon.open.api;

import com.newcoupon.application.service.discount.DiscountCalculatorService;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.OpenResource;
import com.newcoupon.open.api.dto.CalculateRequest;
import com.newcoupon.open.api.dto.CalculateResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 优惠计算试算接口（开放面）。
 *
 * <p><b>开放面契约（S1）</b>：/open/v1/ 前缀自动落入三过滤器——
 * 签名认证（appId 进 RequestContext，body 不携带身份）、配额限流、
 * <b>X-Client-Token 幂等</b>（同 token 同参返回缓存响应——幂等键是请求头，不是 body 字段）。
 *
 * <p><b>支持全部三种券类型</b>（满减/满折/无门槛）：引擎按券模板的类型字段选策略。
 *
 * <p><b>为什么是试算</b>：下单前用户要知道"用这张券能省多少"，此时不锁券；
 * 引擎只读券规则不改状态，核销时平台再按权威数据复算（第 2 步接入）。
 */
@RestController
@RequestMapping("/open/v1")
public class CouponCalculateController {

    private final DiscountCalculatorService calculatorService;

    public CouponCalculateController(DiscountCalculatorService calculatorService) {
        this.calculatorService = calculatorService;
    }

    @PostMapping("/calculate")
    @OpenResource("coupons/calculate")
    public Result<CalculateResponse> calculate(@Valid @RequestBody CalculateRequest request) {
        return Result.success(calculatorService.calculate(request));
    }
}
