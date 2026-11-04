package com.newcoupon.controller;

import com.newcoupon.application.dto.TemplateQueryDtos.TemplatePage;
import com.newcoupon.application.service.TemplateQueryService;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板列表（V6 2D 控制台最小页，仅内部面 /api）。
 *
 * <p><b>为什么不在 {@link TemplateController} 里加这个方法</b>：那个类是双前缀映射
 * （/api/templates + /open/v1/templates），方法加进去会同时出现在开放面——而平台列表
 * 是**全租户视角**（不按 app_id 过滤），签名调用方可枚举别人的券模板，是跨租户泄漏。
 * 独立成类把列表钉死在 /api 一个前缀上。
 *
 * <p><b>方法安全</b>：template:read（V44 种子权限码，四个平台角色都有）。
 * 认证由 LegacyAdminTokenAuthenticationFilter 承担（/api 前缀所有方法），
 * 控制台会话与旧令牌都能到达这里。
 *
 * <p><b>参数协议</b>：page 从 1 起（缺省 1）、size 默认 20 上限 100；非法值 400 + 1000
 * （与 AdminAuthController 的参数拒绝同一形状——"请求没构造对"不是业务结果）。
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateQueryController {

    private final TemplateQueryService queryService;

    public TemplateQueryController(TemplateQueryService queryService) {
        this.queryService = queryService;
    }

    /** 分页列表：id 倒序，返回 total/page/size/rows（rows=表格八列投影）。 */
    @GetMapping
    @PreAuthorize("hasAuthority('template:read')")
    public ResponseEntity<Result<TemplatePage>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int p = page != null ? page : 1;
        int s = size != null ? size : TemplateQueryService.DEFAULT_SIZE;
        if (p < 1) {
            return ResponseEntity.badRequest()
                    .body(Result.fail(ErrorCode.PARAM_ERROR, "page 从 1 起"));
        }
        if (s < 1 || s > TemplateQueryService.MAX_SIZE) {
            return ResponseEntity.badRequest()
                    .body(Result.fail(ErrorCode.PARAM_ERROR,
                            "size 须在 1~" + TemplateQueryService.MAX_SIZE + " 之间"));
        }
        return ResponseEntity.ok(Result.success(queryService.list(p, s)));
    }
}
