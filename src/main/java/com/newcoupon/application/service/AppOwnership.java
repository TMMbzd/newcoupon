package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.common.context.RequestContext;
import com.newcoupon.domain.open.OpenApp;

/**
 * 归属守卫（S1 第 8 步）——多租户越权防线。
 *
 * <p><b>为什么在应用服务层而不是过滤器</b>：过滤器阶段只知道"你是谁"（appId），
 * 不知道"你要碰的是哪个模板/哪张券"（那要解析 body、甚至要查库）。
 * <b>过滤器管身份，服务层管归属</b>——这条边界决定了越权检查必须贴着资源读取点写，
 * 而不是在入口一刀切。
 *
 * <p><b>内部面（{@code /api/**}）为什么放行</b>：内部面没有 {@code RequestContext}
 * （{@code appId == null}），它代表平台自己（运营要能看全局、跑对账、做巡检）。
 * 这是刻意的"管理员例外"，不是漏判——**判据是"身份来源"，不是"路径前缀"**：
 * 只有真正的开放面请求（签名认证过）才带 appId。
 *
 * <p><b>资源描述里禁止带上"它属于谁"</b>：错误信息只回显调用方自己的 appId。
 * 告诉 A 应用"这张券属于 B 应用"本身就是一次信息泄漏。
 */
public final class AppOwnership {

    private AppOwnership() {
    }

    /** 当前请求的接入方；内部面（无上下文）返回 null，语义是"平台自己" */
    public static String currentAppId() {
        return RequestContext.current().appId();
    }

    /**
     * 新建资源的归属方：开放面 = 调用方；内部面 = 内置应用（{@code app_id='1'}）。
     *
     * <p>内部面必须落到一个**真实存在的应用**上（V14 迁移写了内置应用记录），
     * 否则归属列就成了空值——空值迟早会被某个查询当成"大家的"。
     */
    public static String ownerForNewResource() {
        String appId = currentAppId();
        return appId != null ? appId : OpenApp.BUILTIN_APP_ID;
    }

    /**
     * 越权校验：开放面要求资源归属与调用方一致；内部面（管理员）放行。
     *
     * @param resourceAppId 资源行上的归属列
     * @param resourceDesc  资源描述（如 {@code coupon:123}）——**只写资源本身，不写它属于谁**
     */
    public static void assertAccessible(String resourceAppId, String resourceDesc) {
        String appId = currentAppId();
        if (appId != null && !appId.equals(resourceAppId)) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN,
                    resourceDesc + " 不属于应用 " + appId);
        }
    }
}
