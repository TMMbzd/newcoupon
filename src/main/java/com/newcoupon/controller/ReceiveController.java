package com.newcoupon.controller;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.OpenResource;
import com.newcoupon.common.context.SwitchGuard;
import com.newcoupon.application.service.SwitchKey;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 领券接口（C 端）。duplicated=true 表示重放/并发命中，调用方视为成功。
 *
 * <p><b>两个前缀，两种契约（S1 D1 决策）</b>：{@code /api/coupons} 是内部/管理面（现状不动），
 * {@code /open/v1/coupons} 是开放面（走 {@code OpenApiAuthFilter}：签名 + 配额 + 幂等）。
 * 同一份代码挂两个前缀，业务逻辑一份、契约面两份——避免"两份协议转换代码长期漂移"。
 */
@RestController
@RequestMapping({"/api/coupons", "/open/v1/coupons"})
public class ReceiveController {

    private final ReceiveAppService receiveAppService;

    public ReceiveController(ReceiveAppService receiveAppService) {
        this.receiveAppService = receiveAppService;
    }

    @PostMapping("/receive")
    @OpenResource("coupons/receive")
    @SwitchGuard(SwitchKey.RECEIVE)
    public Result<ReceiveResult> receive(@Valid @RequestBody ReceiveRequest req) {
        return Result.success(receiveAppService.receive(req));
    }
}
