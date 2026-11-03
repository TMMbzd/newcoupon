package com.newcoupon.controller;

import com.newcoupon.application.service.ConfirmExceptionService;
import com.newcoupon.application.service.ConfirmExceptionService.PendingException;
import com.newcoupon.application.service.ConfirmExceptionService.ResolveResult;
import com.newcoupon.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付后核销异常管理面（环 2）。
 *
 * <p><b>只在内部面</b>：{@code /api/**} 的非 GET 请求由 {@code LegacyAdminTokenAuthenticationFilter}（V6 2A 前叫 AdminTokenFilter）
 * 默认要求 {@code X-Admin-Token}（不在业务写白名单里，无需登记）；开放面没有这个入口。
 *
 * <p>GET 沿用项目现状（管理面只读不设令牌，见 00/07 的信任边界）；真正的 RBAC
 * 在环 4。列表只返回定位订单所需的标识，不返回用户 PII。
 */
@RestController
@RequestMapping("/api/pricing-exceptions")
public class PricingExceptionController {

    private final ConfirmExceptionService service;

    public PricingExceptionController(ConfirmExceptionService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<PendingException>> list(
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(service.listPending(limit));
    }

    @PostMapping("/{usageNo}/resolve")
    public Result<ResolveResult> resolve(@PathVariable String usageNo,
                                         @Valid @RequestBody ResolveRequest req) {
        return Result.success(service.resolve(usageNo, req.action(), req.reason()));
    }

    /** CONFIRM=按快照原价核销；RELEASE=确认订单已退款/不用该优惠后释放 */
    public record ResolveRequest(
            @NotBlank(message = "action 不能为空") @Size(max = 16) String action,
            @Size(max = 512, message = "处理说明最长 512 字符") String reason) {
    }
}
