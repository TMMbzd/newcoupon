package com.newcoupon.controller;

import com.newcoupon.common.Result;
import com.newcoupon.infrastructure.audit.AdminActionAuditor;
import com.newcoupon.infrastructure.ops.DrainHealthIndicator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * <p><b>认证与授权（审查 M1）</b>：认证归 {@code LegacyAdminTokenAuthenticationFilter}
 * （/internal/admin/** 与 /api 管理面同一判据：旧令牌或会话）；授权与其他管理写口一样由
 * {@code @PreAuthorize('ops:drain')} 终审（V50 种子：ADMIN/OPERATOR 有、AUDITOR 无）——
 * 全项目不变量是"过滤器只认证不授权"，摘流量是"把实例请出集群"的能力，只读角色
 * 拿着它能逐台摘光全部实例（全站无声 outage），必须在授权层挡下。
 *
 * <p><b>审计</b>：drain/undrain 经 {@link AdminActionAuditor} 留痕
 * （INTERNAL_DRAIN/INTERNAL_UNDRAIN，resourceId=实例标识）——"谁在什么时候摘了哪台的
 * 流量"是排障与追责的第一问题；被 @PreAuthorize 拒掉的请求到不了服务层、不留服务层
 * 审计（与全项目约定一致，拒绝本身有安全链 7012 响应）。
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

    /** 审计 action：摘流量（resourceId=实例标识） */
    static final String AUDIT_DRAIN = "INTERNAL_DRAIN";
    /** 审计 action：回流量 */
    static final String AUDIT_UNDRAIN = "INTERNAL_UNDRAIN";

    private final DrainHealthIndicator drainGate;
    private final AdminActionAuditor adminActionAuditor;
    /** 实例标识（复用缓存失效的 instance-id：每 JVM 唯一，审计 resourceId 用它） */
    private final String instanceId;

    public InternalAdminController(DrainHealthIndicator drainGate,
                                   AdminActionAuditor adminActionAuditor,
                                   @Value("${coupon.cache.invalidation.instance-id:unknown}")
                                   String instanceId) {
        this.drainGate = drainGate;
        this.adminActionAuditor = adminActionAuditor;
        this.instanceId = instanceId;
    }

    /**
     * 置摘除标志：/actuator/health 立即变 503/DOWN，在途请求继续处理完。
     * 幂等：重复 drain 不报错（滚动脚本重试安全），data 恒为当前状态。
     */
    @PostMapping("/drain")
    @PreAuthorize("hasAuthority('ops:drain')")
    public Result<Boolean> drain() {
        drainGate.drain();
        log.warn("internal admin: drain 调用，当前状态 drained={}（instance={})",
                drainGate.isDrained(), instanceId);
        adminActionAuditor.record(AUDIT_DRAIN, "instance", instanceId,
                Map.of("drained", drainGate.isDrained()));
        return Result.success(drainGate.isDrained());
    }

    /**
     * 恢复服务态（health 回 UP）：运维兜底——摘错了不用重启实例。
     * 幂等：未摘除时 undrain 也是 200，data=false。
     */
    @PostMapping("/undrain")
    @PreAuthorize("hasAuthority('ops:drain')")
    public Result<Boolean> undrain() {
        drainGate.undrain();
        log.info("internal admin: undrain 调用，当前状态 drained={}（instance={})",
                drainGate.isDrained(), instanceId);
        adminActionAuditor.record(AUDIT_UNDRAIN, "instance", instanceId,
                Map.of("drained", drainGate.isDrained()));
        return Result.success(drainGate.isDrained());
    }
}
