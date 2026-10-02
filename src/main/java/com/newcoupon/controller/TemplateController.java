package com.newcoupon.controller;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TemplateResponse;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.OpenResource;
import com.newcoupon.common.context.SwitchGuard;
import com.newcoupon.application.service.SwitchKey;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板接口（M 端）。Controller 只做协议转换：HTTP ↔ DTO ↔ 应用服务调用。
 * 不写业务逻辑、不碰仓储——保持"薄"，业务演进时这层永远不用改。
 *
 * <p>两个前缀两种契约（S1 D1）：{@code /api/templates} 走管理令牌（平台运营），
 * {@code /open/v1/templates} 走签名（接入方自助建券）——**同一段业务代码，两套身份体系**。
 * 接入方只能用签名面，因此天然拿不到"管理令牌能做的事"（如查看别人的应用）。
 */
@RestController
@RequestMapping({"/api/templates", "/open/v1/templates"})
public class TemplateController {

    private final TemplateAppService templateAppService;

    public TemplateController(TemplateAppService templateAppService) {
        this.templateAppService = templateAppService;
    }

    /** 建券。@Valid 触发 JSR-303 校验，失败由全局异常处理器统一转 Result */
    @PostMapping
    @OpenResource("templates/create")
    public Result<TemplateAppService.CreatedTemplate> create(@Valid @RequestBody TemplateCreateRequest req) {
        return Result.success(templateAppService.create(req));
    }

    /** 发布（未发布 → 已发布，条件更新防并发重复发布） */
    @PostMapping("/{id}/publish")
    @OpenResource("templates/publish")
    @SwitchGuard(SwitchKey.TEMPLATE_PUBLISH)
    public Result<Void> publish(@PathVariable long id) {
        templateAppService.publish(id);
        return Result.success();
    }

    /** 详情 */
    @GetMapping("/{id}")
    @OpenResource("templates/get")
    public Result<TemplateResponse> get(@PathVariable long id) {
        return Result.success(templateAppService.get(id));
    }
}
