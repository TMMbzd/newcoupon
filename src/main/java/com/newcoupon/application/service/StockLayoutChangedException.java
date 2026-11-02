package com.newcoupon.application.service;

/**
 * 调用方持有的布局版本与事务内 DB 当前模板的 {@code stock_layout_version} 不一致。
 *
 * <p>它是**事务内信号**：必须在任何日计数/库存更新之前抛出，让事务整体回滚；
 * 同步入口收到后失效模板缓存、重读并重路由一次，异步 worker 收到后把任务退回 PENDING
 * （不是 REJECTED——布局变化不是业务拒绝，重试到新版本仍可发券）。
 */
public class StockLayoutChangedException extends RuntimeException {

    private final long templateId;
    private final int expectedVersion;
    private final int actualVersion;

    public StockLayoutChangedException(long templateId, int expectedVersion, int actualVersion) {
        super("stock layout changed: templateId=" + templateId
                + ", expectedVersion=" + expectedVersion + ", actualVersion=" + actualVersion);
        this.templateId = templateId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public long templateId() {
        return templateId;
    }

    public int expectedVersion() {
        return expectedVersion;
    }

    public int actualVersion() {
        return actualVersion;
    }
}
