package com.newcoupon.domain.repository;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
import java.util.List;
import java.util.Optional;

/**
 * 模板仓储端口（领域层定义接口，基础设施层提供实现——端口-适配器模式）。
 *
 * <p>为什么接口定义在 domain、实现在 infrastructure：
 * 领域逻辑只依赖"我需要存取什么"，不依赖 MyBatis/SQL 细节——
 * 换存储、写单测（Mockito 假实现）都不动领域代码。这是 DDD 六边形架构的核心收益。
 *
 * <p>方法设计遵循"一个方法 = 一种真实的业务读写场景"，禁止通用 CRUD 接口泄漏到领域：
 * {@link #casUpdatePublishStatus} 直接表达"状态机条件迁移"语义（CAS），
 * 影响行数为 0 即并发冲突/状态不满足。
 */
public interface TemplateRepository {

    /** 新建模板，回填主键 */
    CouponTemplate save(CouponTemplate template);

    /** 按主键查询 */
    Optional<CouponTemplate> findById(long id);

    /** 已发布模板清单：库存对账定时任务用 */
    List<CouponTemplate> findPublished();

    /**
     * 条件更新发布状态（CAS）：UPDATE ... SET publish_status=to
     *   WHERE id=? AND publish_status=from
     *
     * @return true=更新成功；false=影响行数为 0（已被并发变更，调用方按状态冲突处理）
     */
    boolean casUpdatePublishStatus(long id, PublishStatus from, PublishStatus to);

    /**
     * 条件扣库存（ADR-0006 双防线的 DB 侧）：issued_count+1，守卫
     * {@code issued_count < total_count AND publish_status=1 AND approve_status=1}。
     * 检查与写入一条 SQL 原子完成——即使 Redis 闸门额度异常偏大，这里仍保证零超发。
     *
     * @return 影响行数：1=成功；0=售罄或状态竞态（调用方应抛信号，由编排层事务外新快照分类）
     */
    int casDeductStock(long templateId);
}
