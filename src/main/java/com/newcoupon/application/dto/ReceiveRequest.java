package com.newcoupon.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 领券请求 DTO。
 *
 * <p>requestId 语义（study/01a Q10）：调用方（前端/上游服务）为"一次领取意图"生成的编号，
 * 网络超时重试时必须复用同一个——它配合服务端唯一索引保证"重试不重发"。
 *
 * <p>⚠️ 已登记简化（STATUS L12）：userId 由请求携带（无登录态）；ROADMAP M6 接入登录态后
 * 改为从 token 解析，**绝不信任参数里的身份**（现在是"没有登录态"而不是"身份可以伪造"）。
 *
 * <p>source/bizType/bizId（V6 阶段 1D，V43 事件归因三件套）：**服务端内部字段，不出现在
 * HTTP 契约里**——四条发放链路在构造本对象时各自填值（同步入口由 ReceiveAppService 补
 * SYNC，批量 worker 填 GRANT+taskNo，兑换码填 CODE+batchNo，异步任务填 ASYNC），最终随
 * {@code CouponIssueTxService.issueC1} 落进 coupon_event 的归因列。全部可空：不填则事件
 * 归因列落 NULL，不影响任何既有行为。appId/activityId 不在这里——它们从模板行取快照。
 */
public record ReceiveRequest(

        @NotBlank(message = "requestId 不能为空（网络重试时请复用同一个）")
        @Size(max = 64)
        @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}",
                message = "requestId 仅限字母/数字/下划线/连字符（outbox payload JSON 契约安全——终审 P2）")
        String requestId,

        @NotNull(message = "userId 不能为空")
        @Min(1)
        Long userId,

        @NotNull(message = "templateId 不能为空")
        @Min(1)
        Long templateId,

        String source,

        String bizType,

        String bizId
) {

    /** 兼容既有调用点的三参便捷构造：无链路标记（事件归因列落 NULL）。 */
    public ReceiveRequest(String requestId, Long userId, Long templateId) {
        this(requestId, userId, templateId, null, null, null);
    }

    /**
     * 返回补上默认链路标记的副本：source 为空才补，调用方显式指定的不改写。
     * 同步入口（ReceiveAppService）用它落 SYNC——其余三条链路构造时已自带 source，
     * 经过它时是原样返回。
     */
    public ReceiveRequest withSourceIfAbsent(String defaultSource) {
        return source == null
                ? new ReceiveRequest(requestId, userId, templateId, defaultSource, bizType, bizId)
                : this;
    }
}
