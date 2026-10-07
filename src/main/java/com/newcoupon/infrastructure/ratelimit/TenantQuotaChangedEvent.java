package com.newcoupon.infrastructure.ratelimit;

/**
 * 租户配额可能变了（迁移第 5 步，2026-10-08）。
 *
 * <p><b>为什么需要这个事件</b>：Sentinel 的规则来自数据源，而数据源是**定时拉取**的
 * （默认 10 秒）。若不主动推，就会出现一个真实的缺口：
 * <b>新建一个租户之后，它在最多 10 秒内不受配额限制</b>——实测就是这么红的。
 *
 * <p>迁移前的语义是"改配置本实例立即生效、其他实例等刷新"，这个事件用来保住前半句。
 *
 * <p><b>为什么用事件而不是直接调用</b>：{@code TenantFlowRuleDataSource} 依赖
 * {@code TenantConfigService}（它要读配置），如果反过来让服务直接调数据源，
 * 就成了循环依赖。事件把这条回边解开了——发消息的人不需要知道谁在听。
 */
public record TenantQuotaChangedEvent(String reason) {
}
