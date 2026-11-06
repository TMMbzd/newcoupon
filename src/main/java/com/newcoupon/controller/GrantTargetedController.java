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
 * 定向发放接口（V6 阶段 6，grant_type=2）。
 *
 * <p>与 {@link GrantTaskController} 的批量发放共用同一套任务域：建任务后由同一个
 * worker 领取 chunk、逐人复用 C1 领取链路发券，幂等键/预算/取消/重试语义完全一致。
 * 差异只在受理标记 {@code grant_type=2}（运营报表与调用方据此区分）。
 *
 * <p>双前缀照 1A 先例：{@code /api/grant/targeted} 走管理令牌，
 * {@code /open/v1/grant/targeted} 走签名；归属校验在服务层。
 */
@RestController
@RequestMapping({"/api/grant/targeted", "/open/v1/grant/targeted"})
public class GrantTargetedController {

    private final GrantTaskService grantTaskService;

    public GrantTargetedController(GrantTaskService grantTaskService) {
        this.grantTaskService = grantTaskService;
    }

    /** 受理定向发放：userId 列表去重后逐人发一张；异步执行。 */
    @PostMapping
    @OpenResource("grant/targeted")
    public Result<GrantTaskCreateResult> create(@Valid @RequestBody GrantTaskCreateRequest req) {
        return Result.success(grantTaskService.createTargetedTask(req));
    }

    /** 进度查询：与批量任务同一份状态口径。 */
    @GetMapping("/{taskNo}")
    @OpenResource("grant/targeted/get")
    public Result<GrantTaskStatus> get(@PathVariable String taskNo) {
        return Result.success(grantTaskService.getStatus(taskNo));
    }

    /** 取消受理：已发放的券不受影响，只阻止未执行的 item。 */
    @PostMapping("/{taskNo}/cancel")
    @OpenResource("grant/targeted/cancel")
    public Result<GrantTaskCancelResult> cancel(@PathVariable String taskNo) {
        return Result.success(grantTaskService.cancel(taskNo));
    }
}
