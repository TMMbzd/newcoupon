package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponStockShardPO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * C1 库存分片 Mapper（V25，ADR-0018；V37 起按 layout_version 版本化）。
 *
 * <p>热路径只有 {@link #casDeduct} 一条：UPDATE 的 WHERE 里同时带
 * {@code layout_version}、{@code status=0} 和 {@code used<quota}，检查与写入在同一条语句内完成；
 * 影响 0 行就是“这片满了/停发/布局已退役”，调用方换下一片或按布局变化重试。
 *
 * <p><b>全局 vs 版本</b>：{@link #sumQuota} / {@link #sumUsed} 是**所有版本**的合计，
 * 供 {@code GateRebuildService} 的守恒公式使用（Σquota(all)=total、Σused(all)=Σ券）；
 * 布局/调整/对账按 {@code template.stock_layout_version} 读 {@code *Version} 方法。
 */
@Mapper
public interface CouponStockShardMapper extends BaseMapper<CouponStockShardPO> {

    /**
     * 领券热路径：对**当前布局版本**的本片做条件扣减。
     * 0 行 = 该片已满/停发/版本已退役；调用方顺序探测下一片。
     */
    @Update("UPDATE coupon_stock_shard SET used = used + 1, version = version + 1 "
            + "WHERE template_id = #{templateId} AND layout_version = #{layoutVersion} "
            + "AND shard_no = #{shardNo} AND status = 0 AND used < quota")
    int casDeduct(@Param("templateId") long templateId,
                  @Param("layoutVersion") int layoutVersion,
                  @Param("shardNo") int shardNo);

    /** 全版本读（对账/诊断）：按版本、片号排序，便于断言全局 Σquota、Σused。 */
    @Select("SELECT * FROM coupon_stock_shard WHERE template_id = #{templateId} "
            + "ORDER BY layout_version, shard_no")
    List<CouponStockShardPO> selectByTemplate(@Param("templateId") long templateId);

    /** 单版本读：发布幂等校验、调整、冻结/重上线只操作 active version。 */
    @Select("SELECT * FROM coupon_stock_shard WHERE template_id = #{templateId} "
            + "AND layout_version = #{layoutVersion} ORDER BY shard_no")
    List<CouponStockShardPO> selectByTemplateVersion(@Param("templateId") long templateId,
                                                     @Param("layoutVersion") int layoutVersion);

    /**
     * 单版本加行锁读：Slice B 的 reshard 需要按 shard_no 顺序锁 active 行；
     * 现在提供，避免届时再改 Mapper 契约。
     */
    @Select("SELECT * FROM coupon_stock_shard WHERE template_id = #{templateId} "
            + "AND layout_version = #{layoutVersion} ORDER BY shard_no FOR UPDATE")
    List<CouponStockShardPO> selectByTemplateVersionForUpdate(
            @Param("templateId") long templateId, @Param("layoutVersion") int layoutVersion);

    @Select("SELECT COUNT(*) FROM coupon_stock_shard WHERE template_id = #{templateId}")
    long countByTemplate(@Param("templateId") long templateId);

    @Select("SELECT COUNT(*) FROM coupon_stock_shard WHERE template_id = #{templateId} "
            + "AND layout_version = #{layoutVersion}")
    long countByTemplateVersion(@Param("templateId") long templateId,
                                @Param("layoutVersion") int layoutVersion);

    @Select("SELECT COUNT(*) FROM coupon_stock_shard WHERE template_id = #{templateId} "
            + "AND layout_version = #{layoutVersion} AND status = 0")
    long countActive(@Param("templateId") long templateId,
                     @Param("layoutVersion") int layoutVersion);

    @Select("SELECT COUNT(*) FROM coupon_stock_shard WHERE template_id = #{templateId} "
            + "AND layout_version = #{layoutVersion} AND status = 0 AND used < quota")
    long countSellable(@Param("templateId") long templateId,
                       @Param("layoutVersion") int layoutVersion);

    /** 全局合计（所有版本）：gate 重建的守恒公式；不要改成单版本。 */
    @Select("SELECT COALESCE(SUM(quota), 0) FROM coupon_stock_shard WHERE template_id = #{templateId}")
    long sumQuota(@Param("templateId") long templateId);

    /** 全局合计（所有版本）：与 COUNT(user_coupon) 对账；不要改成单版本。 */
    @Select("SELECT COALESCE(SUM(used), 0) FROM coupon_stock_shard WHERE template_id = #{templateId}")
    long sumUsed(@Param("templateId") long templateId);

    /**
     * 批量全局合计（V6 2D 控制台列表）：一次 IN 聚合取一批模板各自的 Σused（全版本）。
     * 控制台列表页"已领"列用真实口径（coupon_template.issued_count 是 V10 冻结快照，
     * C1 下恒 0——ADR-0018 §3.1）。一条 IN 聚合而不是逐行 SUM：size 上限 100 →
     * IN 集合 ≤ 100，主键索引 (template_id, ...) 走 100 次范围扫，没有 N+1。
     * 返回的 PO 只填 templateId + used 两列。
     */
    @Select("<script>SELECT template_id AS templateId, SUM(used) AS used "
            + "FROM coupon_stock_shard WHERE template_id IN "
            + "<foreach collection='templateIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY template_id</script>")
    List<CouponStockShardPO> sumUsedBatch(@Param("templateIds") java.util.Collection<Long> templateIds);

    @Select("SELECT COALESCE(SUM(quota), 0) FROM coupon_stock_shard "
            + "WHERE template_id = #{templateId} AND layout_version = #{layoutVersion}")
    long sumQuotaVersion(@Param("templateId") long templateId,
                         @Param("layoutVersion") int layoutVersion);

    @Select("SELECT COALESCE(SUM(used), 0) FROM coupon_stock_shard "
            + "WHERE template_id = #{templateId} AND layout_version = #{layoutVersion}")
    long sumUsedVersion(@Param("templateId") long templateId,
                        @Param("layoutVersion") int layoutVersion);

    /** 调整库存：只加 active version 的未发额度。管理操作低频，不需要热路径优化。 */
    @Update("UPDATE coupon_stock_shard SET quota = quota + #{delta}, version = version + 1 "
            + "WHERE template_id = #{templateId} AND layout_version = #{layoutVersion} "
            + "AND shard_no = #{shardNo}")
    int casAddQuota(@Param("templateId") long templateId,
                    @Param("layoutVersion") int layoutVersion,
                    @Param("shardNo") int shardNo,
                    @Param("delta") long delta);

    /**
     * 调整库存：只减 active version 的未发额度。{@code quota - used >= delta} 是硬守卫——
     * 已发出的券不能被“减库存”抹掉；返回 0 行说明该片没有足够未发额度。
     */
    @Update("UPDATE coupon_stock_shard SET quota = quota - #{delta}, version = version + 1 "
            + "WHERE template_id = #{templateId} AND layout_version = #{layoutVersion} "
            + "AND shard_no = #{shardNo} AND quota - used >= #{delta}")
    int casReduceQuota(@Param("templateId") long templateId,
                       @Param("layoutVersion") int layoutVersion,
                       @Param("shardNo") int shardNo,
                       @Param("delta") long delta);

    /** 下线/冻结/重上线：只动 active version；退役版本保持只读。 */
    @Update("UPDATE coupon_stock_shard SET status = #{status}, version = version + 1 "
            + "WHERE template_id = #{templateId} AND layout_version = #{layoutVersion}")
    int updateStatusAll(@Param("templateId") long templateId,
                        @Param("layoutVersion") int layoutVersion,
                        @Param("status") int status);

    /**
     * 退役一个版本：停发并把额度归零到 used（剩余额度已搬到新版本）。
     * 只用于 reshard 事务内，调用方已对该版本行加锁并做过守恒校验。
     */
    @Update("UPDATE coupon_stock_shard SET status = 1, quota = used, version = version + 1 "
            + "WHERE template_id = #{templateId} AND layout_version = #{layoutVersion} "
            + "AND used <= quota")
    int retireVersion(@Param("templateId") long templateId,
                      @Param("layoutVersion") int layoutVersion);

    /**
     * 删除一个**没有发过券**的版本（回滚专用）。SQL 自身带 used=0 守卫：
     * 即使调用方断言漏掉，也不会删掉已发库存的账。
     */
    @Delete("DELETE FROM coupon_stock_shard WHERE template_id = #{templateId} "
            + "AND layout_version = #{layoutVersion} AND used = 0 AND status IN (0, 1)")
    int deleteUnusedVersion(@Param("templateId") long templateId,
                            @Param("layoutVersion") int layoutVersion);
}
