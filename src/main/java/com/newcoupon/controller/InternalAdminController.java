package com.newcoupon.controller;

import com.newcoupon.common.Result;
import com.newcoupon.infrastructure.ops.DrainHealthIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实例内部运维端点（V6 5B 生产化模拟刀 A1）：{@code POST /internal/admin/drain} 先摘流量再 SIGTERM。
 *
 * <p><b>给谁用</b>：滚动重启的编排方（运维脚本/发布系统）。标准序列：
 * <pre>
 *   drain → 轮询确认 LB 已摘除（health 503 且 LB 不再把新请求路由过来）
 *        → SIGTERM（graceful 30s 收尾在途请求）→ 重启 → 等 health UP → LB 回池
 * </pre>
 * 先 drain 再 SIGTERM 的意义：graceful shutdown 的 30s 窗口里实例还在集群里，
 * LB 仍会把新请求路由过来，窗口末尾的请求可能来不及完成；先摘流量让"停止接新请求"
 * 提前到 SIGTERM 之前发生，SIGTERM 时只剩在途请求要收尾。
 *
 * <p><b>认证归属</b>：/internal/admin/** 与 /api 管理面同一判据（X-Admin-Token 或会话，
 * 见 {@code LegacyAdminTokenAuthenticationFilter#isProtected}）——摘流量是"把实例请出
 * 集群"的能力，裸奔等于任何人都能源源不断地把整个集群逐台摘光（等效 DoS）。
 *
 * <p><b>选型说明——为什么 health 503 而不是关端口</b>：见
 * {@link DrainHealthIndicator} 类注释（端口活着是 graceful 排空与 LB 探测的共同前提）。
 *
 * <p><b>为什么在 /internal 前缀而不是 /api</b>：这不是业务管理面的资源 CRUD，是实例
 * 自身的运行时状态口——语义上是"对这台实例"而不是"对券数据"。独立前缀让安全判据、
 * 网络策略（生产可对 /internal 单独限制来源网段）都有独立的收口位置。
 */
@RestController
@RequestMapping("/internal/admin")
public class InternalAdminController {

    private static final Logger log = LoggerFactory.getLogger(InternalAdminController.class);

    private final DrainHealthIndicator drainGate;

    public InternalAdminController(DrainHealthIndicator drainGate) {
        this.drainGate = drainGate;
    }

    /**
     * 置摘除标志：/actuator/health 立即变 503/DOWN，在途请求继续处理完。
     * 幂等：重复 drain 不报错（滚动脚本重试安全），data 恒为当前状态。
     */
    @PostMapping("/drain")
    public Result<Boolean> drain() {
        drainGate.drain();
        return Result.success(drainGate.isDrained());
    }

    /**
     * 恢复服务态（health 回 UP）：运维兜底——摘错了不用重启实例。
     * 幂等：未摘除时 undrain 也是 200，data=false。
     */
    @PostMapping("/undrain")
    public Result<Boolean> undrain() {
        drainGate.undrain();
        log.info("internal admin: undrain 调用，当前状态 drained={}", drainGate.isDrained());
        return Result.success(drainGate.isDrained());
    }
}
