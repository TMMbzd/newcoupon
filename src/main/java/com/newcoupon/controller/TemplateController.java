package com.newcoupon.controller;

import com.newcoupon.application.dto.TemplateCreateRequest;
import com.newcoupon.application.dto.TemplateResponse;
import com.newcoupon.application.service.TemplateAppService;
import com.newcoupon.common.Result;
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
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateAppService templateAppService;

    public TemplateController(TemplateAppService templateAppService) {
        this.templateAppService = templateAppService;
    }

    /** 建券。@Valid 触发 JSR-303 校验，失败由全局异常处理器统一转 Result */
    @PostMapping
    public Result<TemplateAppService.CreatedTemplate> create(@Valid @RequestBody TemplateCreateRequest req) {
        return Result.success(templateAppService.create(req));
    }

    /** 发布（未发布 → 已发布，条件更新防并发重复发布） */
    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable long id) {
        templateAppService.publish(id);
        return Result.success();
    }

    /** 详情 */
    @GetMapping("/{id}")
    public Result<TemplateResponse> get(@PathVariable long id) {
        return Result.success(templateAppService.get(id));
    }
}
