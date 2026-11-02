package com.newcoupon.controller;

import com.newcoupon.application.dto.GrantTaskCancelResult;
import com.newcoupon.application.dto.GrantTaskCreateRequest;
import com.newcoupon.application.dto.GrantTaskCreateResult;
import com.newcoupon.application.dto.GrantTaskStatus;
import com.newcoupon.application.service.GrantTaskService;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.OpenResource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量发放任务接口（V6 阶段 1A）。Controller 只做协议转换，照
 * {@link TemplateController} 的双前缀模式：{@code /api/grant/tasks} 走管理令牌，
 * {@code /open/v1/grant/tasks} 走签名——同一段业务代码，两套身份体系。
 * 归属校验放服务层（模板/任务加载之后），过滤器阶段不知道任务属于谁。
 */
@RestController
@RequestMapping({"/api/grant/tasks", "/open/v1/grant/tasks"})
public class GrantTaskController {

    private final GrantTaskService grantTaskService;

    public GrantTaskController(GrantTaskService grantTaskService) {
        this.grantTaskService = grantTaskService;
    }

    /** 受理批量发放：校验 + 幂等 + 单事务建 task/chunks/items；异步执行。 */
    @PostMapping
    @OpenResource("grant/task")
    public Result<GrantTaskCreateResult> create(@Valid @RequestBody GrantTaskCreateRequest req) {
        return Result.success(grantTaskService.createTask(req));
    }

    /** 进度查询：计数 + percent；调用方轮询直到终态。 */
    @GetMapping("/{taskNo}")
    @OpenResource("grant/task/get")
    public Result<GrantTaskStatus> get(@PathVariable String taskNo) {
        return Result.success(grantTaskService.getStatus(taskNo));
    }

    /** 取消受理：已发放的券不受影响，只阻止未执行的 item。 */
    @PostMapping("/{taskNo}/cancel")
    @OpenResource("grant/task/cancel")
    public Result<GrantTaskCancelResult> cancel(@PathVariable String taskNo) {
        return Result.success(grantTaskService.cancel(taskNo));
    }
}
