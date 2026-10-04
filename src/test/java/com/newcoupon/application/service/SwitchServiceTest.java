package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 开关的**三层取值**（S2 第 10 步）——<b>红绿灯</b>。
 *
 * <pre>
 * 租户层（open_app_config: switch.receive.enabled）  ← 最具体，有则用
 *     ↓ 没配
 * 全站层（coupon.switch.receive.enabled）
 *     ↓ 没配
 * 默认 true
 * </pre>
 *
 * <p><b>红</b>：U1、U2 会失败——此时 {@code isOn(key, appId)} 还是骨架（忽略租户层），
 * 配了租户开关也不生效。这正是"实现了但没人守"最容易出现的形态：
 * 接口有了、参数有了、注释写了，**逻辑没接上**。
 *
 * <p><b>为什么每条分支都要一条用例</b>：漏掉任一条，删掉对应的那一层代码都不会有人发现——
 * <ul>
 *   <li>U1 杀"忽略租户层"（租户 true 被全站 false 压住）</li>
 *   <li>U2 杀"优先级反转"（租户 false 被全站 true 压住）</li>
 *   <li>U3 杀"跳过全站层"（把"没配"当成"取默认 true"）</li>
 *   <li>U4 杀"缺省即 false"（会让所有没配过的开关静默关闭）</li>
 *   <li>U5 杀"非法值放行"（借非法值绕过全站关闭）</li>
 *   <li>U6 钉住内部面的语义（无租户上下文只用全站层）</li>
 *   <li>U7 钉住读取失败的兜底（保命组件不能变成故障放大器）</li>
 * </ul>
 */
class SwitchServiceTest {

    private static final String APP = "app-sw";
    private static final String GLOBAL_KEY = "coupon.switch.receive.enabled";

    private final MockEnvironment env = new MockEnvironment();
    private final TenantConfigService tenantConfig = mock(TenantConfigService.class);

    private SwitchService svc() {
        return new SwitchService(env, tenantConfig);
    }

    /** 让"租户层"回答一个值；传 null 表示"该租户没配过这一项" */
    private void tenantSays(String value) {
        when(tenantConfig.getRaw(APP, SwitchKey.RECEIVE.configKey())).thenReturn(value);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("U1 租户 true + 全站 false → 开（租户层最具体，必须压过全站）")
    void u1_tenantTrueOverridesGlobalFalse() {
        env.setProperty(GLOBAL_KEY, "false");
        tenantSays("true");

        assertThat(svc().isOn(SwitchKey.RECEIVE, APP))
                .as("这个租户单独开了——若返回 false，说明租户层根本没接上")
                .isTrue();
    }

    @Test
    @DisplayName("U2 租户 false + 全站 true → 关（优先级不能反转）")
    void u2_tenantFalseWinsOverGlobalTrue() {
        env.setProperty(GLOBAL_KEY, "true");
        tenantSays("false");

        assertThat(svc().isOn(SwitchKey.RECEIVE, APP))
                .as("租户自己关掉了——若返回 true，说明读的还是全站层")
                .isFalse();
    }

    @Test
    @DisplayName("U3 租户没配 + 全站 false → 关（继承全站层，而不是取默认值）")
    void u3_unconfiguredTenantInheritsGlobal() {
        env.setProperty(GLOBAL_KEY, "false");
        tenantSays(null);

        assertThat(svc().isOn(SwitchKey.RECEIVE, APP))
                .as("没配过 ≠ 默认开——要往下继承全站层的 false")
                .isFalse();
    }

    @Test
    @DisplayName("U4 两层都没配 → 开（默认全开：缺省不能让功能莫名消失）")
    void u4_defaultsToOn() {
        tenantSays(null);   // 全站也没配

        assertThat(svc().isOn(SwitchKey.RECEIVE, APP))
                .as("开关是「出事时关」不是「上线时开」——没配就是开")
                .isTrue();
    }

    @Test
    @DisplayName("U5 租户值非法 → 忽略该层、回落全站（不能借非法值绕过全站关闭）")
    void u5_invalidTenantValueFallsBackToGlobal() {
        env.setProperty(GLOBAL_KEY, "false");
        tenantSays("flase");   // 拼错

        assertThat(svc().isOn(SwitchKey.RECEIVE, APP))
                .as("非法值若按「保持开」处理，这个租户就绕过了全站关闭——必须回落全站层")
                .isFalse();
    }

    @Test
    @DisplayName("U6 无租户上下文（内部面/定时任务）→ 只用全站层，两个重载等价")
    void u6_noTenantContextUsesGlobalOnly() {
        env.setProperty(GLOBAL_KEY, "false");
        tenantSays("true");   // 就算配了，没有 appId 也不该被读到

        assertThat(svc().isOn(SwitchKey.RECEIVE, null))
                .as("内部面没有接入方身份——租户层对它不生效（这是有意的语义不对称）")
                .isFalse();
        assertThat(svc().isOn(SwitchKey.RECEIVE))
                .as("无参重载应当等价于 appId=null")
                .isFalse();
    }

    @Test
    @DisplayName("U7 读租户配置抛异常 → 跳过租户层、回落全站（不把 DB 故障穿成请求失败）")
    void u7_tenantReadFailureFallsBackToGlobal() {
        env.setProperty(GLOBAL_KEY, "false");
        when(tenantConfig.getRaw(APP, SwitchKey.RECEIVE.configKey()))
                .thenThrow(new RuntimeException("DB down"));

        assertThat(svc().isOn(SwitchKey.RECEIVE, APP))
                .as("开关判断挂在全路径拦截器上——它抛异常等于把所有接口一起打挂")
                .isFalse();
    }
}
