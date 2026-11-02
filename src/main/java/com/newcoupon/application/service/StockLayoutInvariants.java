package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;

/**
 * 版本化库存布局的纯不变量判断（无副作用，供对账与单测共用）。
 *
 * <p>全局不变量（所有版本）：
 * <ul>
 *   <li>Σquota = coupon_template.total_count；</li>
 *   <li>Σused = COUNT(user_coupon)；</li>
 * </ul>
 * 版本不变量：
 * <ul>
 *   <li>active version 行数 = max(1, stock_shard_count)；</li>
 *   <li>退役行（layout_version &lt; active）必须 {@code status=1 且 quota=used}——
 *       剩余额度已经搬到新版本，旧行只保留“已发数量”的只读账；</li>
 *   <li>每行 {@code 0 &lt;= used &lt;= quota}、status ∈ {0,1}、layout_version &gt;= 1。</li>
 * </ul>
 *
 * <p>为什么单独抽类：这些等式在发布/对账/未来的 reshard 工具/单测里都要用，
 * 复制必然漂移；纯函数也让“退役行必须 quota=used”可以脱离数据库直接钉住。
 */
final class StockLayoutInvariants {

    private StockLayoutInvariants() {
    }

    static boolean globalQuotaMatches(long sumQuotaAllVersions, long totalCount) {
        return sumQuotaAllVersions == totalCount;
    }

    static boolean globalUsedMatches(long sumUsedAllVersions, long couponCount) {
        return sumUsedAllVersions == couponCount;
    }

    static boolean activeRowCountMatches(long activeRows, int stockShardCount) {
        return activeRows == Math.max(1, stockShardCount);
    }

    static boolean rowRangeValid(CouponStockShardPO row) {
        long quota = nz(row.getQuota());
        long used = nz(row.getUsed());
        return quota >= 0 && used >= 0 && used <= quota;
    }

    static boolean statusValid(CouponStockShardPO row) {
        Integer status = row.getStatus();
        return status != null && (status == 0 || status == 1);
    }

    static boolean layoutVersionValid(CouponStockShardPO row) {
        Integer version = row.getLayoutVersion();
        return version != null && version >= 1;
    }

    static boolean active(CouponStockShardPO row, int activeVersion) {
        Integer version = row.getLayoutVersion();
        return version != null && version == activeVersion;
    }

    /**
     * 退役行合法性：版本严格早于 active，且是停发 + 额度归零（quota=used）。
     * 版本号大于 active 的行不是“退役”，是脏数据（回滚残留/错写）。
     */
    static boolean retiredRowBalanced(CouponStockShardPO row, int activeVersion) {
        Integer version = row.getLayoutVersion();
        return version != null
                && version < activeVersion
                && Integer.valueOf(1).equals(row.getStatus())
                && nz(row.getQuota()) == nz(row.getUsed());
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
