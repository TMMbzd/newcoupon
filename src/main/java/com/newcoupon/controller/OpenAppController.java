package com.newcoupon.controller;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.AppView;
import com.newcoupon.application.service.OpenAppService.AppWithSecret;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入方管理接口（S1 的"运营面"）。
 *
 * <p><b>为什么挂在 /api 而不是 /open/v1</b>：/api 是<b>内部/管理面</b>（X-Admin-Token 鉴权，
 * 平台运营用）；/open/v1 是<b>开放面</b>（appId+签名鉴权，接入方用）。接入方<b>不能</b>
 * 给自己注册应用——那等于自助开门。所以本 Controller 永不出现在开放面上。
 *
 * <p><b>为什么写操作要 POST /{appId}/status 而不是 PUT /{appId}</b>：只暴露"状态机允许的迁移"
 * （启/停），而不是"整行覆盖"。整行更新接口迟早会被用来改 appId/密钥状态这类不该被随手改的字段。
 *
 * <p>注意 {@code GET} 查询不设防（与 /api/templates 一致）：只读面公开，写操作才要令牌。
 */
@RestController
@RequestMapping("/api/apps")
public class OpenAppController {

    private final OpenAppService openAppService;

    public OpenAppController(OpenAppService openAppService) {
        this.openAppService = openAppService;
    }

    /** 注册接入方。返回体里的 secret 是**明文 SK，只此一次** */
    @PostMapping
    public Result<AppWithSecret> create(@RequestBody CreateAppRequest req) {
        return Result.success(openAppService.create(req));
    }

    /** 接入方列表（含各自密钥状态，不含明文） */
    @GetMapping
    public Result<List<AppView>> list() {
        return Result.success(openAppService.list());
    }

    /** 单个接入方详情 */
    @GetMapping("/{appId}")
    public Result<AppView> get(@PathVariable String appId) {
        return Result.success(openAppService.get(appId));
    }

    /** 启停：停用后该应用的一切签名调用被拒（6002） */
    @PostMapping("/{appId}/status")
    public Result<Void> setStatus(@PathVariable String appId, @RequestBody SetStatusRequest req) {
        if (req.status() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "status 不能为空（0=停用 1=启用）");
        }
        openAppService.setStatus(appId, req.status());
        return Result.success();
    }

    /** 密钥轮换：签发新 SK（明文只此一次），旧 SK 进入轮换期 */
    @PostMapping("/{appId}/rotate")
    public Result<AppWithSecret> rotate(@PathVariable String appId) {
        return Result.success(openAppService.rotate(appId));
    }

    public record SetStatusRequest(Integer status) {
    }
}
