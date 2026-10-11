package com.newcoupon.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计标记（第 1 环 1-1 项）——标在需要审计的方法上。
 *
 * <p>切面（{@link com.newcoupon.infrastructure.audit.AuditAspect}）拦截带本注解的方法，
 * 自动记录六要素：谁（appId/userId）、何时、何处（IP）、何事（action/resourceType/resourceId）、
 * 前后值快照（入参/出参 JSON 序列化）、链路 ID（traceId）。
 *
 * <p>使用方式：
 * <pre>{@code
 * @AuditLog(action = "COUPON_CALCULATE", resourceType = "COUPON", describe = "优惠试算")
 * public CalculateResponse calculate(CalculateRequest request) { ... }
 * }</pre>
 *
 * <p>设计约束：审计写入是异步的（不阻塞业务方法），但哈希链的计算是同步的
 * （synchronized，保证链的连续性）。审计写入失败只记 ERROR 日志，不影响业务操作。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 操作类型：机器可读的标识（如 COUPON_CALCULATE / COUPON_CONFIRM / TEMPLATE_PUBLISH） */
    String action();

    /** 资源类型：被操作的领域对象类型（如 COUPON / TEMPLATE / USAGE） */
    String resourceType();

    /** 人类可读的描述（写入审计记录的 describe 字段，便于运营查看） */
    String describe() default "";
}
