package com.newcoupon.controller;

import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.AppView;
import com.newcoupon.application.service.OpenAppService.AppWithSecret;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.application.service.TenantLifecycleService;
import com.newcoupon.application.service.TenantLifecycleTxService;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * <p>鉴权（2A-2 翻面后）：GET 与写方法同样要"X-Admin-Token 或控制台会话"——
 * 只读面匿名公开的时代已结束（2026-11-03 起匿名 GET 401+7002，公告见
 * docs/runbook/管理面认证翻面.md；判据在 LegacyAdminTokenAuthenticationFilter）。
 */
@RestController
@RequestMapping("/api/apps")
public class OpenAppController {

    private final OpenAppService openAppService;
    private final com.newcoupon.application.service.TenantConfigService tenantConfigService;
    private final TenantLifecycleService tenantLifecycleService;
    private final ObjectMapper objectMapper;

    public OpenAppController(OpenAppService openAppService,
                             com.newcoupon.application.service.TenantConfigService tenantConfigService,
                             TenantLifecycleService tenantLifecycleService,
                             ObjectMapper objectMapper) {
        this.openAppService = openAppService;
        this.tenantConfigService = tenantConfigService;
        this.tenantLifecycleService = tenantLifecycleService;
        this.objectMapper = objectMapper;
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

    /** 读该租户的全部显式配置（未配置的项不出现，用默认值） */
    @GetMapping("/{appId}/config")
    public Result<java.util.Map<String, String>> getConfig(@PathVariable String appId) {
        return Result.success(tenantConfigService.allOf(appId));
    }

    /**
     * 批量写配置（新增或修改）。
     *
     * <p><b>先全部校验、再整体写入</b>：一批里有一个不合法就整体拒绝——
     * 否则运维看到"报错了"，却不知道哪几项已经生效。
     */
    @PutMapping("/{appId}/config")
    public Result<Void> putConfig(@PathVariable String appId,
                                  @RequestBody java.util.Map<String, String> values,
                                  jakarta.servlet.http.HttpServletRequest request) {
        tenantConfigService.put(appId, values, operatorOf(request));
        return Result.success();
    }

    /** 删除一项配置（回落默认值） */
    @DeleteMapping("/{appId}/config/{key}")
    public Result<Void> deleteConfig(@PathVariable String appId, @PathVariable String key,
                                     jakarta.servlet.http.HttpServletRequest request) {
        tenantConfigService.remove(appId, key, operatorOf(request));
        return Result.success();
    }

    /**
     * 生命周期迁移（V6 2B）：按显式迁移表白名单执行（见 AppLifecycle），迁移与事件留痕同事务。
     *
     * <p>动作集：submit_review / approve_sandbox / activate / suspend / resume /
     * close / confirm_close / delete。delete 需双人复核——请求体另带
     * secondApproverUsername + secondApproverPassword（另一个 enabled 管理员的登录密码，
     * 最小双人实现，完整审批表登记给 2C，见 TenantLifecycleService 的裁剪声明）。
     *
     * <p>权限是 tenant:lifecycle（影响面跨租户的治理动作，PLATFORM_OPERATOR 不持有）。
     */
    @PostMapping("/{appId}/lifecycle")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('tenant:lifecycle')")
    public Result<TenantLifecycleTxService.TransitionResult> lifecycle(
            @PathVariable String appId,
            @RequestBody TenantLifecycleService.LifecycleRequest req) {
        return Result.success(tenantLifecycleService.transition(appId, req));
    }

    /**
     * 租户数据导出（V6 2B，一次性）：返回 JSON 快照（app 行 + 模板 id 清单 +
     * 每模板累计发放数），响应头 X-Data-Sha256 是体的 SHA-256。
     * 只允许注销完成（CLOSED）之前导；已导出过再请求 4002（闸门判据是
     * lifecycle 事件表里的 data_export 行）。裁剪：不做券明细/NDJSON/manifest 管道。
     */
    @GetMapping("/{appId}/data-export")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('tenant:lifecycle')")
    public ResponseEntity<byte[]> dataExport(@PathVariable String appId) throws Exception {
        TenantLifecycleTxService.ExportSnapshot snapshot = tenantLifecycleService.export(appId);
        // LinkedHashMap 保证字段序稳定——SHA-256 要可复现，调用方才能在任意时刻重算比对
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("appId", snapshot.appId());
        payload.put("appName", snapshot.appName());
        payload.put("environment", snapshot.environment());
        payload.put("lifecycleStatus", snapshot.lifecycleStatus());
        payload.put("createTime", String.valueOf(snapshot.createTime()));
        payload.put("templateIds", snapshot.templateIds());
        payload.put("issuedCountByTemplate", snapshot.issuedCountByTemplate());
        byte[] body = objectMapper.writeValueAsBytes(payload);
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(body);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return ResponseEntity.ok()
                .header("X-Data-Sha256", hex.toString())
                .header("Content-Disposition", "attachment; filename=data-export-" + appId + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** 变更审计里的操作者标识：管理面没有用户身份，记"令牌 + 来源 IP" */
    private static String operatorOf(jakarta.servlet.http.HttpServletRequest request) {
        return "admin@" + com.newcoupon.infrastructure.web.RiskControlFilter.clientIp(request);
    }

    public record SetStatusRequest(Integer status) {
    }
}
