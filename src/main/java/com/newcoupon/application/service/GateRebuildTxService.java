package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponIssueTaskMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponStockShardMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * gate 重建公式的一致性快照读（讨论 02 核对结论 P1）。
 *
 * <p>三次查询（Σquota、Σused、在途任务数）必须在同一个事务快照里完成：任务从"在途"
 * 迁移到"已发"的瞬间，跨快照读会把同一张券双算（目标偏低 1，假售罄方向、静默）或漏算
 * （偏高 1，多受理方向、安全）。同库 REPEATABLE READ 下，事务内第一条读建立快照，
 * 之后三条读看到同一时刻——窗口消除。
 *
 * <p>单独一个类而不是在 {@link GateRebuildService} 上加注解：@Transactional 经代理生效，
 * 同类内部调用（rebuild 调本类方法）不走代理；Redis 写回也不该圈进 DB 事务（持连接做网络 IO）。
 */
@Service
public class GateRebuildTxService {

    private final CouponStockShardMapper stockShardMapper;
    private final CouponIssueTaskMapper taskMapper;

    public GateRebuildTxService(CouponStockShardMapper stockShardMapper,
                                CouponIssueTaskMapper taskMapper) {
        this.stockShardMapper = stockShardMapper;
        this.taskMapper = taskMapper;
    }

    /** 重建目标 = max(0, Σquota(all) − Σused(all) − 在途任务)。三次读同一快照。 */
    @Transactional(readOnly = true)
    public long computeRemaining(long templateId) {
        long remaining = stockShardMapper.sumQuota(templateId)
                - stockShardMapper.sumUsed(templateId)
                - taskMapper.countInFlight(templateId);
        return Math.max(0, remaining);
    }
}
