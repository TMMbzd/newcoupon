package com.newcoupon.controller;

import com.newcoupon.application.service.SwitchService;
import com.newcoupon.application.service.SwitchService.SwitchView;
import com.newcoupon.common.Result;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开关读取接口（S1 第 9 步）——运维的"我能关什么"清单。
 *
 * <p>只读、不设防（与 {@code GET /api/templates} 一致）：开关清单不含敏感信息，
 * 而"能读到当前状态"本身就是排障的第一步（"我明明关了怎么还生效" →
 * 一看 {@code configured:false} 就知道配错了地方）。
 *
 * <p><b>接口只读不写</b>：改开关走配置（或 S2 的开关中心），不在这里开一个写口——
 * 一个能被 HTTP 改的开关，等于把"关掉核心功能"的能力暴露在网络上。
 */
@RestController
@RequestMapping("/api/switches")
public class SwitchController {

    private final SwitchService switchService;

    public SwitchController(SwitchService switchService) {
        this.switchService = switchService;
    }

    /**
     * 开关清单。带 {@code appId} 时按该租户的三层取值解析，并给出**来源**
     * （{@code TENANT} / {@code GLOBAL} / {@code DEFAULT}）。
     *
     * <p>不带 {@code appId} 时保持原样（只看全站层）——兼容既有调用方，
     * 也对应"内部面没有接入方身份"这一语义。
     */
    @GetMapping
    public Result<List<SwitchView>> list(
            @RequestParam(value = "appId", required = false) String appId) {
        return Result.success(switchService.all(appId));
    }
}
