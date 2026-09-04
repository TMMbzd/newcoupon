package com.newcoupon.domain.repository;

import com.newcoupon.domain.coupon.CouponTemplate;
import com.newcoupon.domain.coupon.PublishStatus;
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

    /**
     * 条件更新发布状态（CAS）：UPDATE ... SET publish_status=to
     *   WHERE id=? AND publish_status=from
     *
     * @return true=更新成功；false=影响行数为 0（已被并发变更，调用方按状态冲突处理）
     */
    boolean casUpdatePublishStatus(long id, PublishStatus from, PublishStatus to);
}
