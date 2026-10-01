package com.newcoupon.common.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源名声明（S1 治理插槽②）——标在 Controller 方法上，限流 / 熔断 / 监控 / 开关共用这一个 key。
 *
 * <p><b>为什么不直接从 URL 推导</b>：URL 会随版本（v1→v2）和重构（路径结构调整）变化，
 * 而资源名一旦进了限流规则、监控面板、开关配置，就是**对外承诺的一部分**——它变了，
 * 这些配置全部要跟着改，还会让历史监控数据断代。显式声明把"契约"钉在代码里，
 * 路径怎么改都不影响。
 *
 * <p>{@link RequestContext#resourceName()} 的完整形态是 {@code 资源名[:维度[:值]]...}——
 * 维度由应用服务补（那时候才知道 templateId），例：{@code coupons/receive:template:2001}。
 *
 * <p>未标注解的方法由过滤器的路径推导兜底（{@code /open/v1/} 之后的路径片段）——
 * 保证任何开放接口都有一个稳定的资源名，不会因为忘了标注解就没有限流 key。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpenResource {

    /** 资源名，形如 {@code coupons/receive}——用路径形态便于与接口一一对应 */
    String value();
}
