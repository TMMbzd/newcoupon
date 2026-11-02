package com.newcoupon.controller;

import com.newcoupon.application.dto.ReceiveAsyncResult;
import com.newcoupon.application.dto.ReceiveAsyncStatusResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.ReceiveAsyncAppService;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.OpenResource;
import com.newcoupon.common.context.SwitchGuard;
import com.newcoupon.application.service.SwitchKey;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ReceiveAsyncAppService receiveAsyncAppService;

    public ReceiveController(ReceiveAppService receiveAppService,
                             ReceiveAsyncAppService receiveAsyncAppService) {
        this.receiveAppService = receiveAppService;
        this.receiveAsyncAppService = receiveAsyncAppService;
    }

    @PostMapping("/receive")
    @OpenResource("coupons/receive")
    @SwitchGuard(SwitchKey.RECEIVE)
    public Result<ReceiveResult> receive(@Valid @RequestBody ReceiveRequest req) {
        return Result.success(receiveAppService.receive(req));
    }

    /**
     * 异步受理：HTTP 202 只表示任务已持久化，不表示已发券。
     * 客户端用下面的状态接口轮询最终结果。
     */
    @PostMapping("/receive-async")
    @OpenResource("coupons/receive-async")
    @SwitchGuard(SwitchKey.RECEIVE)
    public ResponseEntity<Result<ReceiveAsyncResult>> receiveAsync(
            @Valid @RequestBody ReceiveRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.success(receiveAsyncAppService.receive(req)));
    }

    /**
     * 状态查询。当前没有登录态：开放面从签名上下文取 appId；内部面必须显式传 appId+userId。
     * 三个条件缺一不可，不能只按 requestId 查（唯一键本身也含 userId）。
     */
    @GetMapping("/receive-async/{requestId}")
    @OpenResource("coupons/receive-async/status")
    public Result<ReceiveAsyncStatusResult> receiveAsyncStatus(
            @PathVariable String requestId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String appId) {
        return Result.success(receiveAsyncAppService.status(appId, userId, requestId));
    }
}
