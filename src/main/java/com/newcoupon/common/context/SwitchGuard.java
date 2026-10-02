package com.newcoupon.common.context;

import com.newcoupon.application.service.SwitchKey;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 开关守卫（S1 治理插槽③）——标在 Controller 方法上，由拦截器统一裁决。
 *
 * <p><b>为什么用注解而不是在每个服务里 if</b>：
 * <ol>
 *   <li><b>开关位置可见</b>：一个接口受不受开关控制，看方法上的注解就知道——
 *       散在服务层里的 {@code if} 要找半天，还容易漏；</li>
 *   <li><b>业务代码零侵入</b>：应用服务不必知道"开关"这个概念（它也不知道 HTTP 是什么），
 *       依赖方向干净；</li>
 *   <li><b>拦截点统一</b>：所有 HTTP 入口的开关都在一处生效，不会出现"这个接口关了、
 *       那个接口忘了关"。</li>
 * </ol>
 *
 * <p>非 HTTP 的开关（如发件箱投递循环）不走这里——那种场景直接在代码里判
 * {@code SwitchService#isOn}，因为它的"入口"不是一个方法调用。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SwitchGuard {

    /** 该接口受哪个开关控制 */
    SwitchKey value();
}
