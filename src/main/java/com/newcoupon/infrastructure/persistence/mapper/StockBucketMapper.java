package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.StockBucketPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 分桶账本 Mapper（P2b v2）。原则同 TemplateMapper：
 * 涉及并发控制的写操作必须手写带条件的 CAS SQL，禁止读改写整行 updateById。
 */
@Mapper
public interface StockBucketMapper extends BaseMapper<StockBucketPO> {

    /**
     * 逐笔条件扣（A 簇——超发红线的 DB 承重墙，替代原 casDeductStock 的模板行版本）：
     * "检查+写入"一条 SQL 原子完成；affected=0 → Redis 谎报有货（平移攻击/残留旧桶）
     * 或桶行已封账——编排层 CANCEL 回滚 Redis 并落 drift（资损在此被硬防线封顶）。
     * 守卫 status=0：封账旧代不再承载新发放（世代隔离的 DB 侧表达）。
     */
    @Update("UPDATE stock_bucket SET used = used + 1 "
            + "WHERE template_id = #{tpl} AND bucket_no = #{bucket} AND generation = #{gen} "
            + "AND used < quota AND status = 0 "
            + "AND EXISTS (SELECT 1 FROM coupon_template ct WHERE ct.id = stock_bucket.template_id "
            + "  AND ct.publish_status = 1 AND ct.approve_status = 1)")
    int casDeductBucket(@Param("tpl") long tpl,
                        @Param("bucket") int bucket,
                        @Param("gen") int gen);

    /** 封账（定值 CAS）：只置 status，绝不回写 used/quota——整行回写会把并发已提交的扣账抹掉
     * （L38 修复：评审 P1-9；与本类"涉及并发控制的写必须手写条件 SQL"的规矩对齐）。
     * 返回 1=本次封上；0=已是封账/行不存在（幂等）。 */
    @Update("UPDATE stock_bucket SET status = 1 WHERE id = #{id} AND status = 0")
    int seal(@Param("id") long id);

    /** 同代精确读（分代镜像 E3b 的 DB 侧锚；主键新鲜重读用） */
    @Select("SELECT * FROM stock_bucket WHERE template_id = #{tpl} "
            + "AND bucket_no = #{bucket} AND generation = #{gen}")
    StockBucketPO findByTplBucketGen(@Param("tpl") long tpl,
                                     @Param("bucket") int bucket,
                                     @Param("gen") int gen);

    /** 全局已用求和（deep 守卫口径：COUNT(user_coupon) ↔ 全量 SUM(used)——含历史封账代） */
    @Select("SELECT COALESCE(SUM(used), 0) FROM stock_bucket WHERE template_id = #{tpl}")
    long sumUsed(@Param("tpl") long tpl);

    /** 活跃代已用求和（镜像臂口径：Redis dbi 只活在注册代上，历史代 dbi 键不存在——
     * 用全量 SUM 会造成 confirm-lost 永久误报（gen=0 历史行的 gap 不可收敛），三视角审查 P1） */
    @Select("SELECT COALESCE(SUM(used), 0) FROM stock_bucket WHERE template_id = #{tpl} AND status = 0")
    long sumUsedActive(@Param("tpl") long tpl);

    /** 分桶世代已用求和（镜像臂精确口径，2026-09-21 随批量重开修正）：含已封账的真代
     * （其 dbi 键在 c:gens 全世代名册里、两边对齐），只排除 V10 历史代 (0,0) 行
     * （它没有 Redis 键，计入则 confirm-lost 永动）。status 口径不行——批量重开会封掉
     * 已卖完的真代，status=0 求和会漏它们的 used 造成幻影误报 */
    @Select("SELECT COALESCE(SUM(used), 0) FROM stock_bucket WHERE template_id = #{tpl} "
            + "AND NOT (bucket_no = 0 AND generation = 0)")
    long sumUsedBucketed(@Param("tpl") long tpl);

    /** 全量额度求和（含 V10 历史行）——用途：开桶差额判据（历史行也是"已物化"，必须算进差额）
     * 与巡检额度段检查（SUM(quota) ≡ template.allocated，历史行的 quota 也在 allocated 里）。 */
    @Select("SELECT COALESCE(SUM(quota), 0) FROM stock_bucket WHERE template_id = #{tpl}")
    long sumQuota(@Param("tpl") long tpl);

    /** 镜像口径的额度求和：全量行 ∪ 封账真代，只排除 V10 历史行 (0,0)——巡检 E2b 的锚。
     * 为什么与 sumQuota 分开（2026-09-28 评审批 2 修复）：(0,0) 历史行永远没有 Redis 键，
     * 计入 E2b 会让所有存量在售模板（issued_count>0）永久误报守恒破缺（E2a/E2b 是"镜像覆盖"
     * 口径，sumUsedBucketed 排除 (0,0) 是同一先例）。 */
    @Select("SELECT COALESCE(SUM(quota), 0) FROM stock_bucket WHERE template_id = #{tpl} "
            + "AND NOT (bucket_no = 0 AND generation = 0)")
    long sumQuotaRegistered(@Param("tpl") long tpl);

    /** 仍"可卖"的活跃行数（used<quota 且 status=0）——L38（评审 P2-2）钉售罄牌前的 DB 侧权威判据
     * （不用 Redis 余量：污染场景下 Redis 会谎报有货，而 DB 行是权威） */
    @Select("SELECT COUNT(*) FROM stock_bucket WHERE template_id = #{tpl} AND status = 0 AND used < quota")
    long countSellableRows(@Param("tpl") long tpl);

    /** 进入 Redis 镜像口径的全部行（活跃+封账真代，排除 V10 历史行 (0,0)）——ENSURE 灾后重建的输入
     * （2026-09-28 评审批 2：只重建活跃行会让封账真代的 dbi/void 键缺失 → E2b/镜像永久破缺） */
    @Select("SELECT * FROM stock_bucket WHERE template_id = #{tpl} "
            + "AND NOT (bucket_no = 0 AND generation = 0) ORDER BY bucket_no, generation")
    List<StockBucketPO> selectRegistered(@Param("tpl") long tpl);

    /** 活跃桶行清单（开桶选址/巡检遍历用）。同桶多代按代序——ENSURE 重建取 maxGen 的前提（审查 P2 修复） */
    @Select("SELECT * FROM stock_bucket WHERE template_id = #{tpl} AND status = 0 "
            + "ORDER BY bucket_no, generation")
    List<StockBucketPO> selectActive(@Param("tpl") long tpl);

    /** 该桶历史最大世代（重开=+1；V10 历史代 gen=0，新桶从 1 起） */
    @Select("SELECT COALESCE(MAX(generation), 0) FROM stock_bucket WHERE template_id = #{tpl} "
            + "AND bucket_no = #{bucket}")
    int maxGen(@Param("tpl") long tpl, @Param("bucket") int bucket);
}
