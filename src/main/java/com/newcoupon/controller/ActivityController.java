package com.newcoupon.controller;

import com.newcoupon.application.dto.ActivityCreateRequest;
import com.newcoupon.application.dto.ActivityResponse;
import com.newcoupon.application.service.ActivityAppService;
import com.newcoupon.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 营销活动接口（V6 阶段 1C，仅内部面 {@code /api/activities}）。
 * POST 非白名单路径——LegacyAdminTokenAuthenticationFilter 判据反转版自动保护（新增管理写接口
 * 不需要登记即受管）；GET 按全站口径不设防（只读面）。
 * 归属校验在服务层（{@link com.newcoupon.application.service.AppOwnership}）。
 */
@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private final ActivityAppService activityAppService;

    public ActivityController(ActivityAppService activityAppService) {
        this.activityAppService = activityAppService;
    }

    /** 创建活动：预算闸门的容器（budget_enabled=1 时其下模板发放走预算预占）。 */
    @PostMapping
    public Result<ActivityResponse> create(@Valid @RequestBody ActivityCreateRequest req) {
        return Result.success(activityAppService.create(req));
    }

    /** 查询活动。 */
    @GetMapping("/{id}")
    public Result<ActivityResponse> get(@PathVariable long id) {
        return Result.success(activityAppService.get(id));
    }
}
