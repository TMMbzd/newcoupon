package com.newcoupon.infrastructure.metrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SLI 埋点标记（V6 5A）：标在四条链路的 AppService 公开方法上，
 * {@link SliAspect} 据此在服务边界包一层计时 + 结果判定。
 *
 * <p><b>为什么用注解 + 切面而不是在各服务里手写 try/finally</b>：四个入口的包裹逻辑
 * 完全同构（计时 → 执行 → 按出口分类 → 停表），手写四份会有四处可能写错的地方
 * （尤其"异常出口也要记"这一条最容易被漏）；切面让接线位只剩一行注解，
 * 与 {@code @AuditLog} 同一模式。AOP 代理只拦外部调用——四条链路入口全部由
 * Controller 调入，不存在自调用绕过。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SliJourney {

    /** 该方法属于哪条链路（journey 标签取值） */
    SliMetrics.Journey value();
}
