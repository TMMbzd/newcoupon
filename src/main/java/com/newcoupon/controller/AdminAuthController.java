package com.newcoupon.controller;

import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.Result;
import com.newcoupon.infrastructure.security.AdminSessionService;
import com.newcoupon.infrastructure.security.AdminSessionService.AdminLoginException;
import com.newcoupon.infrastructure.security.AdminSessionService.AdminProfile;
import com.newcoupon.infrastructure.security.AdminSessionService.NotLoggedInException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理面身份自服务端点（V6 2A）：登录 / 登出 / 当前身份。
 *
 * <p>前缀 {@code /console/api/auth/**}——这个前缀在安全装配里有三重身份：
 * ① 旧令牌过滤器的显式豁免（登录天然匿名可达，见 AUTH_SERVICE_PREFIX）；
 * ② CSRF 唯一的保护面（写操作要 X-CSRF-TOKEN 头，SPA 先 GET 一次拿 Cookie）；
 * ③ 会话链的用户入口（建立/销毁 SPRING_SECURITY_CONTEXT 会话）。
 *
 * <p>Controller 只做协议转换：状态码语义（401/200）在这里定，锁定计数、密码比对、
 * 会话轮换、审计全在 {@link AdminSessionService}。
 */
@RestController
@RequestMapping("/console/api/auth")
public class AdminAuthController {

    private final AdminSessionService sessionService;

    public AdminAuthController(AdminSessionService sessionService) {
        this.sessionService = sessionService;
    }

    public record LoginRequest(String username, String password) {
    }

    /**
     * 登录：成功 200 + 身份快照 + Set-Cookie(SESSION=轮换后的新 id)；
     * 失败一律 401 + 7011"用户名或密码错误"（用户不存在/密码错/锁定期同文案，防用户名枚举）。
     * CSRF：要 X-CSRF-TOKEN 头（先 GET 任意 /console/api 端点领 Cookie）。
     */
    @PostMapping("/login")
    public ResponseEntity<Result<AdminProfile>> login(@RequestBody LoginRequest req,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        if (req == null || isBlank(req.username()) || isBlank(req.password())) {
            return ResponseEntity.badRequest().body(Result.fail(ErrorCode.PARAM_ERROR, "用户名与密码必填"));
        }
        AdminProfile profile = sessionService.login(req.username().trim(), req.password(), request, response);
        return ResponseEntity.ok(Result.success(profile));
    }

    /** 登出（幂等）：删会话 + 审计。需要 CSRF 头。 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        sessionService.logout(request);
        return Result.success();
    }

    /**
     * 当前身份 + 最新权限。未登录 401 + 7010（前端据此跳登录页）。
     * 权限是本请求刚从 DB 重载的（AdminAuthorityRefreshFilter），改角色立即反映在这里。
     */
    @GetMapping("/me")
    public ResponseEntity<Result<AdminProfile>> me() {
        return ResponseEntity.ok(Result.success(sessionService.me()));
    }

    // 两类预期的"拒绝"异常在本地翻译成状态码：不进 GlobalExceptionHandler 的兜底分支
    //（那会把它们当系统错误吞成 200 + 5000，前端无法区分"没登录"与"系统坏了"）。

    @ExceptionHandler(AdminLoginException.class)
    public ResponseEntity<Result<Void>> loginFailed(AdminLoginException e) {
        return ResponseEntity.status(401).body(Result.fail(ErrorCode.ADMIN_LOGIN_FAILED));
    }

    @ExceptionHandler(NotLoggedInException.class)
    public ResponseEntity<Result<Void>> notLoggedIn(NotLoggedInException e) {
        return ResponseEntity.status(401).body(Result.fail(ErrorCode.ADMIN_SESSION_REQUIRED));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
