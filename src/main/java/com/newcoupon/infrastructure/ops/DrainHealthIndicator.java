package com.newcoupon.infrastructure.ops;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * drain 摘流量标志 + 健康检查联动（V6 5B 生产化模拟刀 A1）。
 *
 * <p><b>它是什么</b>：一个 JVM 级全局标志 + 一个 HealthIndicator。
 * {@code POST /internal/admin/drain} 置位后，本指示器让 /actuator/health 的聚合状态变
 * DOWN（HTTP 503）；undrain 或进程重启后回 UP。仅此而已——不拒绝请求、不断连接。
 *
 * <p><b>为什么用 health 503 摘流量，而不是直接关监听端口</b>：
 * <ul>
 *   <li>LB 的摘除依据就是健康检查（nginx 被动摘除 / K8s readiness / HAProxy check）。
 *       health 变 503 是 LB 唯一认识的"请把我摘掉"语言；</li>
 *   <li>graceful 排空的前提是**端口还活着**：SIGTERM 后 Tomcat 先停止接新连接、
 *       等在途请求完成（spring lifecycle timeout 30s）——这个收尾阶段端口必须能继续
 *       响应 LB 探测，否则 LB 看到的是 connection refused，与"进程崩了"无法区分；</li>
 *   <li>主动断连接（比如 kill 连接池）会把在途领券请求直接掀翻，制造
 *       "扣了库存没落库"的不一致——正是 graceful shutdown 要消灭的东西。</li>
 * </ul>
 *
 * <p><b>drain 之后业务请求的行为</b>：照常处理（这是设计不是漏洞）。drain 只对
 * "要不要给这台实例**新**流量"表态——LB 摘除后新流量自然不来；已经进来的与在途的
 * 请求继续处理完，由后续 SIGTERM 的 graceful shutdown 收尾。单实例直连（没有 LB）的
 * 场景里业务口在 drain 后仍然可用，语义与此一致。
 *
 * <p><b>为什么不是配置中心开关</b>：drain 是**实例级**瞬时状态（进程重启即失效是
 * 正确语义——新进程没理由继承"我已被摘除"），放 Redis/DB 反而引入"摘除状态比进程
 * 活得久"的错误恢复路径。内存 AtomicBoolean 是最小正确的载体。
 */
@Component
public class DrainHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DrainHealthIndicator.class);

    /** false=正常服务；true=已摘除（health DOWN，等 LB 摘掉后 SIGTERM） */
    private final AtomicBoolean drained = new AtomicBoolean(false);

    /** 置摘除标志。返回 true=本次调用发生了状态迁移（之前是服务态）。 */
    public boolean drain() {
        boolean changed = drained.compareAndSet(false, true);
        if (changed) {
            log.warn("DRAIN: 实例进入摘除态——/actuator/health 将返回 503，等 LB 摘除后可 SIGTERM；"
                    + "业务请求继续处理完（不主动断）");
        }
        return changed;
    }

    /** 清摘除标志（运维兜底：摘错了不用重启实例）。返回 true=本次调用发生了状态迁移。 */
    public boolean undrain() {
        boolean changed = drained.compareAndSet(true, false);
        if (changed) {
            log.warn("UNDRAIN: 实例回到服务态——/actuator/health 恢复 UP");
        }
        return changed;
    }

    public boolean isDrained() {
        return drained.get();
    }

    /**
     * 健康贡献：摘除态报 DOWN → 聚合状态 DOWN → /actuator/health 整体 503
     * （Boot 的 HealthHttpCodeStatusMapper 默认把 DOWN 映射为 503）。
     * show-details=never 时响应体只有 {"status":"DOWN"}，不含 detail——正好是 LB
     * 探测需要的最小信息。detail 留给打开详情的排障场景，说明 DOWN 的来源是 drain。
     */
    @Override
    public Health health() {
        if (drained.get()) {
            return Health.down()
                    .withDetail("reason", "drain requested via POST /internal/admin/drain")
                    .build();
        }
        return Health.up().build();
    }
}
