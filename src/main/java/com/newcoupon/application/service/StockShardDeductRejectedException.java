package com.newcoupon.application.service;

/**
 * C1 分片条件更新返回 0 行：该模板所有分片都已满，或分片全部停发/布局缺失。
 *
 * <p>和旧的 {@link StockDeductRejectedException} 一样，它是**事务内信号**：
 * 抛出后整个事务回滚（不会留下半张券），由编排层在事务外读新快照分类
 * “真售罄 / 冻结 / 下线 / 布局未初始化”，再决定返回哪个业务错误码。
 */
public class StockShardDeductRejectedException extends RuntimeException {

    private final long templateId;

    public StockShardDeductRejectedException(long templateId) {
        super("stock shard rejected: templateId=" + templateId);
        this.templateId = templateId;
    }

    public long templateId() {
        return templateId;
    }
}
