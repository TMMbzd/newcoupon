package com.newcoupon.controller;

import com.newcoupon.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 冒烟接口：验证 应用启动 / 统一响应体 / TraceId过滤器 三个基建是否正常。
 * 预期：curl -i http://localhost:8080/api/ping 响应头带 X-Trace-Id，body 是标准 Result。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("pong");
    }
}
