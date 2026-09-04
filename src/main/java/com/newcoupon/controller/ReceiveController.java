package com.newcoupon.controller;

import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.dto.ReceiveResult;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 领券接口（C 端）。duplicated=true 表示重放/并发命中，调用方视为成功。
 */
@RestController
@RequestMapping("/api/coupons")
public class ReceiveController {

    private final ReceiveAppService receiveAppService;

    public ReceiveController(ReceiveAppService receiveAppService) {
        this.receiveAppService = receiveAppService;
    }

    @PostMapping("/receive")
    public Result<ReceiveResult> receive(@Valid @RequestBody ReceiveRequest req) {
        return Result.success(receiveAppService.receive(req));
    }
}
