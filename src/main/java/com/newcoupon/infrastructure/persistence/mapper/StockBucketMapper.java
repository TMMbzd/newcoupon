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
            + "AND used < quota AND status = 0")
    int casDeductBucket(@Param("tpl") long tpl,
                        @Param("bucket") int bucket,
                        @Param("gen") int gen);

    /** 同代精确读（分代镜像 E3b 的 DB 侧锚；主键新鲜重读用） */
    @Select("SELECT * FROM stock_bucket WHERE template_id = #{tpl} "
            + "AND bucket_no = #{bucket} AND generation = #{gen}")
    StockBucketPO findByTplBucketGen(@Param("tpl") long tpl,
                                     @Param("bucket") int bucket,
                                     @Param("gen") int gen);

    /** 全局已用求和（E3c 廉价锚：Σdbi ↔ SUM(used)，16 行求和不打大表） */
    @Select("SELECT COALESCE(SUM(used), 0) FROM stock_bucket WHERE template_id = #{tpl}")
    long sumUsed(@Param("tpl") long tpl);

    /** 全局额度求和（不变量断言：SUM(quota) ≡ template.allocated——巡检 E3a 侧证） */
    @Select("SELECT COALESCE(SUM(quota), 0) FROM stock_bucket WHERE template_id = #{tpl}")
    long sumQuota(@Param("tpl") long tpl);

    /** 活跃桶行清单（开桶选址/巡检遍历用） */
    @Select("SELECT * FROM stock_bucket WHERE template_id = #{tpl} AND status = 0 "
            + "ORDER BY bucket_no")
    List<StockBucketPO> selectActive(@Param("tpl") long tpl);
}
